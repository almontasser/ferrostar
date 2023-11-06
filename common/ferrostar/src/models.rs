use geo::{Coord, LineString, Point};
use serde::Deserialize;
use std::time::SystemTime;

#[cfg(test)]
use serde::Serialize;

#[derive(Clone, Copy, PartialEq, PartialOrd, Debug, uniffi::Record)]
#[cfg_attr(test, derive(Serialize))]
pub struct GeographicCoordinates {
    pub lng: f64,
    pub lat: f64,
}

impl From<Coord> for GeographicCoordinates {
    fn from(value: Coord) -> Self {
        Self {
            lng: value.x,
            lat: value.y,
        }
    }
}

impl From<GeographicCoordinates> for Coord {
    fn from(value: GeographicCoordinates) -> Self {
        Self {
            x: value.lng,
            y: value.lat,
        }
    }
}

impl From<GeographicCoordinates> for Point {
    fn from(value: GeographicCoordinates) -> Self {
        Self(value.into())
    }
}

/// The direction in which the user/device is observed to be traveling.
#[derive(Clone, Copy, PartialEq, PartialOrd, Debug, uniffi::Record)]
pub struct CourseOverGround {
    /// The direction in which the user's device is traveling, measured in clockwise degrees from
    /// true north (N = 0, E = 90, S = 180, W = 270).
    pub degrees: u16,
    /// The accuracy of the course value, measured in degrees.
    pub accuracy: u16,
}

impl CourseOverGround {
    pub fn new(degrees: u16, accuracy: u16) -> Self {
        Self { degrees, accuracy }
    }
}

/// The location of the user that is navigating.
///
/// In addition to coordinates, this includes estimated accuracy and course information,
/// which can influence navigation logic and UI.
#[derive(Clone, Copy, PartialEq, PartialOrd, Debug, uniffi::Record)]
pub struct UserLocation {
    pub coordinates: GeographicCoordinates,
    /// The estimated accuracy of the coordinate (in meters)
    pub horizontal_accuracy: f64,
    pub course_over_ground: Option<CourseOverGround>,
    // TODO: Decide if we want to include heading in the user location, if/how we should factor it in, and how to handle it on Android
    pub timestamp: SystemTime,
}

impl From<UserLocation> for Point {
    fn from(val: UserLocation) -> Point {
        Point::new(val.coordinates.lng, val.coordinates.lat)
    }
}

/// Information describing the series of steps needed to travel between two or more points.
///
/// NOTE: This type is unstable and is still under active development and should be
/// considered unstable.
#[derive(Debug, uniffi::Record)]
#[cfg_attr(test, derive(Serialize))]
pub struct Route {
    pub geometry: Vec<GeographicCoordinates>,
    /// The total route distance, in meters.
    pub distance: f64,
    /// The ordered list of waypoints to visit, including the starting point.
    /// Note that this is distinct from the *geometry* which includes all points visited.
    /// A waypoint represents a start/end point for a route leg.
    pub waypoints: Vec<GeographicCoordinates>,
    pub steps: Vec<RouteStep>,
}

/// A maneuver (such as a turn or merge) followed by travel of a certain distance until reaching
/// the next step.
///
/// NOTE: OSRM specifies this rather precisely as "travel along a single way to the subsequent step"
/// but we will intentionally define this somewhat looser unless/until it becomes clear something
///
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
#[cfg_attr(test, derive(Serialize))]
pub struct RouteStep {
    pub geometry: Vec<GeographicCoordinates>,
    /// The distance, in meters, to travel along the route after the maneuver to reach the next step.
    pub distance: f64,
    pub road_name: Option<String>,
    pub instruction: String,
    pub visual_instructions: Vec<VisualInstructions>,
    // TODO: Spoken instruction
}

impl RouteStep {
    // TODO: Memoize or something later; would also let us drop storage from internal nav state
    pub(crate) fn get_linestring(&self) -> LineString {
        LineString::from_iter(self.geometry.iter().map(|coord| Coord {
            x: coord.lng,
            y: coord.lat,
        }))
    }
}

// TODO: trigger_at doesn't really have to live in the public interface; figure out if we want to have a separate FFI vs internal type

#[derive(Debug, PartialEq, uniffi::Record)]
pub struct SpokenInstruction {
    /// Plain-text instruction which can be synthesized with a TTS engine.
    pub text: String,
    /// Speech Synthesis Markup Language, which should be preferred by clients capable of understanding it.
    pub ssml: Option<String>,
    /// How far (in meters) from the upcoming maneuver the instruction should start being displayed
    pub trigger_distance_before_maneuver: f64,
}

/// Indicates the type of maneuver to perform.
///
/// Frequently used in conjunction with [ManeuverModifier].
#[derive(Deserialize, Debug, Copy, Clone, Eq, PartialEq, uniffi::Enum)]
#[cfg_attr(test, derive(Serialize))]
#[serde(rename_all = "lowercase")]
pub enum ManeuverType {
    Turn,
    #[serde(rename = "new name")]
    NewName,
    Depart,
    Arrive,
    Merge,
    #[serde(rename = "on ramp")]
    OnRamp,
    #[serde(rename = "off ramp")]
    OffRamp,
    Fork,
    #[serde(rename = "end of road")]
    EndOfRoad,
    Continue,
    Roundabout,
    Rotary,
    #[serde(rename = "roundabout turn")]
    RoundaboutTurn,
    Notification,
    #[serde(rename = "exit roundabout")]
    ExitRoundabout,
    #[serde(rename = "exit rotary")]
    ExitRotary,
}

/// Specifies additional information about a [ManeuverType]
#[derive(Deserialize, Debug, Copy, Clone, Eq, PartialEq, uniffi::Enum)]
#[cfg_attr(test, derive(Serialize))]
#[serde(rename_all = "lowercase")]
pub enum ManeuverModifier {
    UTurn,
    #[serde(rename = "sharp right")]
    SharpRight,
    Right,
    #[serde(rename = "slight right")]
    SlightRight,
    Straight,
    #[serde(rename = "slight left")]
    SlightLeft,
    Left,
    #[serde(rename = "sharp left")]
    SharpLeft,
}

#[derive(Debug, Clone, Eq, PartialEq, uniffi::Record)]
#[cfg_attr(test, derive(Serialize))]
pub struct VisualInstructionContent {
    pub text: String,
    pub maneuver_type: Option<ManeuverType>,
    pub maneuver_modifier: Option<ManeuverModifier>,
    pub roundabout_exit_degrees: Option<u16>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
#[cfg_attr(test, derive(Serialize))]
pub struct VisualInstructions {
    pub primary_content: VisualInstructionContent,
    pub secondary_content: Option<VisualInstructionContent>,
    /// How far (in meters) from the upcoming maneuver the instruction should start being displayed
    pub trigger_distance_before_maneuver: f64,
}