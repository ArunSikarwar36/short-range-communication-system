import CoreLocation
import Foundation
import SrcsCore
import SwiftUI
import UIKit

/// GPS via CoreLocation (works without internet). On only while sharing, sending an SOS, or while
/// friends' positions are shown; fixes go to the shared controller.
final class GpsProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private weak var controller: SrcsController?
    private var running = false
    private var background = false

    init(controller: SrcsController) {
        self.controller = controller
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 3
    }

    var authorized: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    func requestPermission() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
    }

    /// [inBackground]: keep updates while the app is in the background (only while sharing).
    func setWanted(_ wanted: Bool, inBackground: Bool) {
        if wanted && !authorized { requestPermission() }
        if background != inBackground {
            background = inBackground
            manager.allowsBackgroundLocationUpdates = inBackground && authorized
            manager.showsBackgroundLocationIndicator = inBackground
        }
        if wanted && authorized && !running {
            running = true
            manager.startUpdatingLocation()
        } else if !wanted && running {
            running = false
            manager.stopUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        controller?.onLocation(
            lat: l.coordinate.latitude, lon: l.coordinate.longitude,
            accuracyM: l.horizontalAccuracy, atMillis: Int64(l.timestamp.timeIntervalSince1970 * 1000)
        )
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if authorized && !running { NotificationCenter.default.post(name: .srcsLocationAuthorized, object: nil) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

extension Notification.Name {
    static let srcsLocationAuthorized = Notification.Name("srcsLocationAuthorized")
}

enum MapLink {
    /// Opens Apple Maps at the position (Apple Maps can download offline maps on iOS 17+).
    static func open(lat: Double, lon: Double, label: String) {
        let q = label.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        if let url = URL(string: "http://maps.apple.com/?ll=\(lat),\(lon)&q=\(q)") {
            UIApplication.shared.open(url)
        }
    }
}

struct SosBanner: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ForEach(model.snap?.sos ?? [], id: \.alertId) { a in
            VStack(alignment: .leading, spacing: 6) {
                Text("SOS from \(a.name)").font(.headline).foregroundColor(.red)
                if !a.note.isEmpty { Text(a.note) }
                Text(a.hasFix ? (a.distanceText.isEmpty ? String(format: "%.5f, %.5f", a.lat, a.lon) : a.distanceText) : "No GPS position in the alert")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    if a.hasFix {
                        Button("Show on map") { MapLink.open(lat: a.lat, lon: a.lon, label: "SOS \(a.name)") }.buttonStyle(.borderedProminent)
                    }
                    Button("Dismiss") { model.controller.dismissSos(alertId: a.alertId) }.buttonStyle(.bordered)
                }
            }
            .listRowBackground(Color.red.opacity(0.18))
        }
    }
}

struct LocationSection: View {
    @EnvironmentObject var model: AppModel
    @State private var choosing = false
    @State private var sosConfirm = false
    @State private var sosNote = ""
    @State private var toPaired = true
    @State private var toGroup = true
    @State private var minutes = 60

    var body: some View {
        let loc = model.snap?.location
        Section {
            if loc?.sharing == true {
                HStack {
                    Circle().fill(Color.green).frame(width: 10, height: 10)
                    VStack(alignment: .leading) {
                        Text("Sharing your location")
                        Text(sharingLine(loc)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Stop") { model.controller.stopSharing() }.buttonStyle(.bordered)
                }
            } else {
                Text("Off. Your position is only sent when you choose to share it, and only to the phones you pick.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Share my location") {
                    model.gps.requestPermission()
                    toGroup = model.snap?.group != nil
                    choosing = true
                }
                .accessibilityIdentifier("shareLocation")
            }
            Button {
                model.gps.requestPermission()
                sosConfirm = true
            } label: {
                Text("SOS").bold().frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            ForEach(model.snap?.friends ?? [], id: \.id) { f in
                HStack {
                    VStack(alignment: .leading) {
                        Text(f.name)
                        Text([f.distanceText.isEmpty ? "Your GPS position is not known yet" : f.distanceText,
                              f.ageSeconds < 60 ? "just now" : "\(f.ageSeconds / 60) min ago",
                              f.viaGroup ? "via group" : ""].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Map") { MapLink.open(lat: f.lat, lon: f.lon, label: f.name) }.buttonStyle(.borderless)
                }
            }
        } header: {
            Text("Location")
        }
        .confirmationDialog("Share my location", isPresented: $choosing, titleVisibility: .visible) {
            Button("Paired phones · 15 min") { start(paired: true, group: false, minutes: 15) }
            Button("Paired phones · 1 hour") { start(paired: true, group: false, minutes: 60) }
            Button("Paired phones · 8 hours") { start(paired: true, group: false, minutes: 480) }
            if model.snap?.group != nil {
                Button("My group · 1 hour") { start(paired: false, group: true, minutes: 60) }
                Button("Paired phones and group · 1 hour") { start(paired: true, group: true, minutes: 60) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Updates are sent when you move (at most every 5 s) and stop automatically at the end.")
        }
        .alert("Send SOS?", isPresented: $sosConfirm) {
            TextField("What happened? (optional)", text: $sosNote)
            Button("Send SOS", role: .destructive) {
                model.sendSos(sosNote)
                sosNote = ""
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every paired phone in range and your group get an alarm with your position (if GPS finds you).")
        }
    }

    private func start(paired: Bool, group: Bool, minutes: Int) {
        model.controller.startSharing(toPaired: paired, toGroup: group, minutes: Int32(minutes))
    }

    private func sharingLine(_ loc: LocationView?) -> String {
        guard let loc = loc else { return "" }
        let until = Date(timeIntervalSince1970: Double(loc.sharingUntil) / 1000)
        let f = DateFormatter()
        f.timeStyle = .short
        let who = [loc.toPaired ? "paired phones" : nil, loc.toGroup ? "your group" : nil].compactMap { $0 }.joined(separator: " and ")
        return "\(who) · until \(f.string(from: until)) · " + (loc.hasFix ? "\(loc.recipients) receiving" : "waiting for GPS")
    }
}
