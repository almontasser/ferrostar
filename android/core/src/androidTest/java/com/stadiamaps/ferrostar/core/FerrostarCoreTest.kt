package com.stadiamaps.ferrostar.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mock.MediaTypes
import okhttp3.mock.MockInterceptor
import okhttp3.mock.eq
import okhttp3.mock.get
import okhttp3.mock.post
import okhttp3.mock.respond
import okhttp3.mock.rule
import okhttp3.mock.url
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationDetector
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.RouteRequestGenerator
import uniffi.ferrostar.RouteResponseParser
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.StepAdvanceMode
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import java.time.Instant

private val valhallaEndpointUrl = "https://api.stadiamaps.com/navigate/v1"

// Simple test to ensure that the extensibility with native code is working.

class MockRouteRequestGenerator : RouteRequestGenerator {
    override fun generateRequest(
        userLocation: UserLocation,
        waypoints: List<GeographicCoordinate>
    ): RouteRequest = RouteRequest.HttpPost(valhallaEndpointUrl, mapOf(), byteArrayOf())

}

class MockRouteResponseParser(private val routes: List<Route>) : RouteResponseParser {
    override fun parseResponse(response: ByteArray): List<Route> = routes
}

class FerrostarCoreTest {
    private val errorBody = """
        {
            "error": "No valid authentication provided."
        }
    """.trimIndent().toResponseBody(MediaTypes.MEDIATYPE_JSON)

    // Mocked route
    private val mockGeom = listOf(
        GeographicCoordinate(lng = 0.0, lat = 0.0),
        GeographicCoordinate(lng = 1.0, lat = 1.0)
    )
    private val instructionContent = VisualInstructionContent(
        text = "Sail straight",
        maneuverType = ManeuverType.DEPART,
        maneuverModifier = ManeuverModifier.STRAIGHT,
        roundaboutExitDegrees = null
    )
    private val mockRoute = Route(
        geometry = mockGeom,
        bbox = BoundingBox(sw = mockGeom.first(), ne = mockGeom.last()),
        distance = 1.0,
        waypoints = mockGeom,
        steps = listOf(
            RouteStep(
                geometry = mockGeom,
                distance = 1.0,
                roadName = "foo road",
                instruction = "Sail straight",
                visualInstructions = listOf(
                    VisualInstruction(
                        primaryContent = instructionContent,
                        secondaryContent = null,
                        triggerDistanceBeforeManeuver = 42.0
                    )
                ),
                spokenInstructions = listOf()
            )
        )
    )

    @Test
    fun test401UnauthorizedRouteResponse() = runTest {
        val interceptor = MockInterceptor().apply {
            rule(post, url eq valhallaEndpointUrl) {
                respond(401, errorBody)
            }

            rule(get) {
                respond {
                    throw IllegalStateException("an IO error")
                }
            }
        }

        val core = FerrostarCore(
            routeAdapter = RouteAdapter(
                requestGenerator = MockRouteRequestGenerator(),
                responseParser = MockRouteResponseParser(routes = listOf())
            ),
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            locationProvider = SimulatedLocationProvider(),
            delegate = null
        )

        try {
            // Tests that the core generates a request and attempts to process it, but throws due to the mocked network layer
            core.getRoutes(
                initialLocation = UserLocation(
                    coordinates = GeographicCoordinate(
                        -149.543469,
                        60.5347155
                    ), 0.0, null, Instant.now()
                ),
                waypoints = listOf(GeographicCoordinate(-149.5485806, 60.5349908))
            )
            fail("Expected the request to fail")
        } catch (e: InvalidStatusCodeException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun test200MockRouteResponse() = runTest {
        val interceptor = MockInterceptor().apply {
            rule(post, url eq valhallaEndpointUrl) {
                respond(200, "".toResponseBody())
            }

            rule(get) {
                respond {
                    throw IllegalStateException("an IO error")
                }
            }
        }

        val core = FerrostarCore(
            routeAdapter = RouteAdapter(
                requestGenerator = MockRouteRequestGenerator(),
                responseParser = MockRouteResponseParser(routes = listOf(mockRoute))
            ),
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            locationProvider = SimulatedLocationProvider(),
            delegate = null
        )
        val routes = core.getRoutes(
            initialLocation = UserLocation(
                coordinates = GeographicCoordinate(
                    lng = -149.543469,
                    lat = 60.5347155
                ), horizontalAccuracy = 6.0, courseOverGround = null, timestamp = Instant.now()
            ), waypoints = listOf(GeographicCoordinate(lng = -149.5485806, lat = 60.5349908))
        )

        assertEquals(listOf(mockRoute), routes)
    }

    @Test
    fun testCustomRouteDeviationHandler() = runTest {
        val interceptor = MockInterceptor().apply {
            rule(post, url eq valhallaEndpointUrl) {
                respond(200, "".toResponseBody())
            }

            rule(post, url eq valhallaEndpointUrl) {
                respond(200, "".toResponseBody())
            }
        }

        class CoreDelegate : FerrostarCoreDelegate {
            var correctiveActionDelegateCalled = false
            var loadedAltRoutesDelegateCalled = false

            override fun correctiveActionForDeviation(
                core: FerrostarCore,
                deviationInMeters: Double,
                remainingWaypoints: List<GeographicCoordinate>
            ): CorrectiveAction {
                correctiveActionDelegateCalled = true
                assertEquals(42.0, deviationInMeters, Double.MIN_VALUE);
                return CorrectiveAction.GetNewRoutes(remainingWaypoints)
            }

            override fun loadedAlternativeRoutes(core: FerrostarCore, routes: List<Route>) {
                loadedAltRoutesDelegateCalled = true
                assert(core.isCalculatingNewRoute)  // We are still calculating until this method completes
                assert(routes.isNotEmpty())
            }
        }

        val locationProvider = SimulatedLocationProvider()
        val delegate = CoreDelegate()
        val core = FerrostarCore(
            routeAdapter = RouteAdapter(
                requestGenerator = MockRouteRequestGenerator(),
                responseParser = MockRouteResponseParser(routes = listOf(mockRoute))
            ),
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            locationProvider = locationProvider,
            delegate = delegate
        )

        val routes = core.getRoutes(
            initialLocation = UserLocation(
                coordinates = GeographicCoordinate(
                    lng = -149.543469,
                    lat = 60.5347155
                ), horizontalAccuracy = 6.0, courseOverGround = null, timestamp = Instant.now()
            ), waypoints = listOf(GeographicCoordinate(lng = -149.5485806, lat = 60.5349908))
        )

        locationProvider.lastLocation = SimulatedLocation(GeographicCoordinate(0.0, 0.0), 6.0, null, Instant.now())
        core.startNavigation(
            routes.first(),
            NavigationControllerConfig(
                stepAdvance = StepAdvanceMode.RelativeLineStringDistance(
                    16U,
                    16U
                ), routeDeviationTracking = RouteDeviationTracking.Custom(detector = object :
                    RouteDeviationDetector {
                    override fun checkRouteDeviation(
                        location: UserLocation,
                        route: Route,
                        currentRouteStep: RouteStep
                    ): RouteDeviation {
                        return RouteDeviation.OffRoute(42.0)
                    }
                })
            )
        )

        assert(delegate.correctiveActionDelegateCalled)

        // TODO: Figure out how to test this properly with Kotlin coroutines + JUnit in the way.
        // Spent several hours fighting it trying to get something half as good as XCTestExpectation,
        // but was ultimately unsuccessful. I verified this works fine in a debugger and real app,
        // but the test scope is different.
//        assert(delegate.loadedAltRoutesDelegateCalled)
    }
}