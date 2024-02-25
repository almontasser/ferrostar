//
//  NavigationView.swift
//  Ferrostar Demo
//
//  Created by Ian Wagner on 2023-10-09.
//

import SwiftUI
import CoreLocation
import FerrostarCore
import FerrostarMapLibreUI

let style = URL(string: "https://tiles.stadiamaps.com/styles/outdoors.json?api_key=\(APIKeys.shared.stadiaMapsAPIKey)")!

struct NavigationView: View {
    
    private let initialLocation = CLLocation(latitude: 37.332726,
                                             longitude: -122.031790)
    
    private var locationProvider: LocationProviding
    @ObservedObject private var ferrostarCore: FerrostarCore
    
    @State private var isFetchingRoutes = false
    @State private var routes: [Route]?
    @State private var errorMessage: String? {
        didSet {
            Task {
                try await Task.sleep(nanoseconds: 10 * NSEC_PER_SEC)
                errorMessage = nil
            }
        }
    }

    init() {
        let simulated = SimulatedLocationProvider(location: initialLocation)
        simulated.warpFactor = 10
        locationProvider = simulated
        self.ferrostarCore = FerrostarCore(
            valhallaEndpointUrl: URL(string: "https://api.stadiamaps.com/route/v1?api_key=\(APIKeys.shared.stadiaMapsAPIKey)")!,
            profile: "pedestrian",
            locationProvider: locationProvider
        )

        // TODO: Example showing delegate
    }
    
    var body: some View {
        let locationServicesEnabled = locationProvider.authorizationStatus == .authorizedAlways
            || locationProvider.authorizationStatus == .authorizedWhenInUse

        NavigationStack {
            NavigationMapView(
                lightStyleURL: style,
                darkStyleURL: style,
                navigationState: ferrostarCore.state,
                initialCamera: .center(initialLocation.coordinate, zoom: 14)
            )
            .overlay(alignment: .bottomLeading) {
                VStack {
                    HStack {
                        if isFetchingRoutes {
                            Text("Loading route...")
                                .font(.caption)
                                .padding(.all, 8)
                                .foregroundColor(.white)
                                .background(Color.black.opacity(0.7).clipShape(.buttonBorder, style: FillStyle()))
                        }
                        
                        if let errorMessage {
                            Text(errorMessage)
                                .font(.caption)
                                .padding(.all, 8)
                                .foregroundColor(.white)
                                .background(Color.red.opacity(0.7).clipShape(.buttonBorder, style: FillStyle()))
                                .onTapGesture {
                                    self.errorMessage = nil
                                }
                        }
                        
                        Spacer()
                        
                        NavigationLink {
                            ConfigurationView()
                        } label: {
                            Image(systemName: "gear")
                        }
                        .padding(.all, 8)
                        .background(
                            Color.white
                                .clipShape(.buttonBorder, style: FillStyle())
                                .shadow(radius: 4)
                        )
                        .padding(.top, 56)
                    }
                    
                    Spacer()
                    
                    HStack {
                        Text(locationLabel)
                            .font(.caption)
                            .padding(.all, 8)
                            .foregroundColor(.white)
                            .background(Color.black.opacity(0.7).clipShape(.buttonBorder, style: FillStyle()))
                        
                        Spacer()
                        
                        if locationServicesEnabled {
                            Button("Start Navigation") {
                                Task {
                                    do {
                                        isFetchingRoutes = true
                                        try await startNavigation()
                                        isFetchingRoutes = false
                                    } catch {
                                        isFetchingRoutes = false
                                        errorMessage = "\(error.localizedDescription)"
                                    }
                                }
                            }
                            .disabled(routes?.isEmpty == true)
                            .padding(.all, 8)
                            .background(
                                Color.white
                                    .clipShape(.buttonBorder, style: FillStyle())
                                    .shadow(radius: 4)
                            )
                        } else {
                            Button("Enable Location Services") {
                                // TODO: enable location services.
                            }
                            .padding(.all, 8)
                            .background(
                                Color.white
                                    .clipShape(.buttonBorder, style: FillStyle())
                                    .shadow(radius: 4)
                            )
                        }
                    }
                }
                .padding()
                .padding(.bottom, 32)
                .task {
                    await getRoutes()
                }
            }
        }
    }
    
    // MARK: Conveniences
    
    func getRoutes() async {
        guard let userLocation = locationProvider.lastLocation else {
            print("No user location")
            return
        }
        
        do {
            let waypoints = locations.map { $0.coordinate }
            routes = try await ferrostarCore.getRoutes(initialLocation: userLocation,
                                                       waypoints: waypoints)
            
            print("DemoApp: successfully fetched a route")
        } catch {
            print("DemoApp: error fetching route: \(error)")
            errorMessage = "\(error)"
        }
    }
    
    func startNavigation() async throws {
        guard let route = routes?.first else {
            print("DemoApp: No route")
            return
        }
        
        // Starts the navigation state machine.
        // It's worth having a look through the parameters,
        // as most of the configuration happens here.
        try ferrostarCore.startNavigation(
            route: route,
            stepAdvance: .relativeLineStringDistance(minimumHorizontalAccuracy: 32, automaticAdvanceDistance: 10),
            routeDeviationTracking: .staticThreshold(minimumHorizontalAccuracy: 25, maxAcceptableDeviation: 20))

        if let simulated = locationProvider as? SimulatedLocationProvider {
            try simulated.startSimulating(route: route)
            print("DemoApp: starting route simulation")
        }
    }
    
    var locationLabel: String {
        guard let userLocation = locationProvider.lastLocation else {
            return "No location - authed as \(locationProvider.authorizationStatus)"
        }
        
        return "±\(Int(userLocation.horizontalAccuracy))m accuracy"
    }

}

#Preview {
    NavigationView()
}
