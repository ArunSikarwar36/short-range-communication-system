import MapLibre
import SrcsCore
import SwiftUI
import UniformTypeIdentifiers

/// Style JSON for one map area, with fonts and icons from the app bundle (no internet).
private func styleURL(for area: MapRegion) -> URL? {
    guard let base = Bundle.main.resourceURL?.appendingPathComponent("map"),
          var json = try? String(contentsOf: base.appendingPathComponent("style-dark.json"), encoding: .utf8) else { return nil }
    json = json.replacingOccurrences(of: "{MAP_URL}", with: "pmtiles://file://\(area.path)")
        .replacingOccurrences(of: "{ASSETS}", with: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
    let out = FileManager.default.temporaryDirectory.appendingPathComponent("srcs-style-\(abs(area.path.hashValue)).json")
    try? json.write(to: out, atomically: true, encoding: .utf8)
    return out
}

struct MapPerson: Equatable {
    let kind: String
    let name: String
    let lat: Double
    let lon: Double
}

/// MapLibre map of a downloaded area with a layer for this phone, friends and SOS alerts.
struct OfflineMapView: UIViewRepresentable {
    let area: MapRegion
    let people: [MapPerson]
    let focus: CLLocationCoordinate2D?

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: styleURL(for: area))
        map.delegate = context.coordinator
        map.allowsRotating = false
        map.minimumZoomLevel = Double(area.minZoom)
        map.maximumZoomLevel = Double(area.maxZoom) + 2
        let center = focus ?? CLLocationCoordinate2D(latitude: area.centerLat, longitude: area.centerLon)
        map.setCenter(center, zoomLevel: min(15, Double(area.maxZoom)), animated: false)
        context.coordinator.areaPath = area.path
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        if context.coordinator.areaPath != area.path {
            context.coordinator.areaPath = area.path
            context.coordinator.source = nil
            map.styleURL = styleURL(for: area)
            map.setCenter(CLLocationCoordinate2D(latitude: area.centerLat, longitude: area.centerLon), zoomLevel: min(15, Double(area.maxZoom)), animated: false)
        }
        context.coordinator.people = people
        context.coordinator.refresh()
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        var areaPath = ""
        var people: [MapPerson] = []
        var source: MLNShapeSource?

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            let src = MLNShapeSource(identifier: "srcs-people", shape: nil, options: nil)
            style.addSource(src)
            func circles(_ id: String, _ kind: String, _ color: UIColor, _ radius: Double, _ stroke: UIColor) {
                let l = MLNCircleStyleLayer(identifier: id, source: src)
                l.predicate = NSPredicate(format: "kind == %@", kind)
                l.circleColor = NSExpression(forConstantValue: color)
                l.circleRadius = NSExpression(forConstantValue: radius)
                l.circleStrokeWidth = NSExpression(forConstantValue: 2.5)
                l.circleStrokeColor = NSExpression(forConstantValue: stroke)
                style.addLayer(l)
            }
            circles("srcs-sos", "sos", UIColor(red: 0.9, green: 0.28, blue: 0.3, alpha: 0.9), 14, .white)
            circles("srcs-friends", "friend", UIColor(red: 0.24, green: 0.86, blue: 0.59, alpha: 1), 9, .black)
            circles("srcs-me", "me", UIColor(red: 0.13, green: 0.78, blue: 0.9, alpha: 1), 9, .white)
            let names = MLNSymbolStyleLayer(identifier: "srcs-names", source: src)
            names.text = NSExpression(forKeyPath: "name")
            names.textFontNames = NSExpression(forConstantValue: ["Noto Sans Medium"])
            names.textFontSize = NSExpression(forConstantValue: 13)
            names.textColor = NSExpression(forConstantValue: UIColor.white)
            names.textHaloColor = NSExpression(forConstantValue: UIColor.black)
            names.textHaloWidth = NSExpression(forConstantValue: 1.5)
            names.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 1.5)))
            names.textAllowsOverlap = NSExpression(forConstantValue: true)
            style.addLayer(names)
            source = src
            refresh()
        }

        func refresh() {
            guard let src = source else { return }
            let features: [MLNPointFeature] = people.map { p in
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                f.attributes = ["kind": p.kind, "name": p.name]
                return f
            }
            src.shape = MLNShapeCollectionFeature(shapes: features)
        }
    }
}

struct MapScreen: View {
    @EnvironmentObject var model: AppModel
    var focusLat: Double? = nil
    var focusLon: Double? = nil
    @State private var selectedPath: String?

    private var people: [MapPerson] {
        guard let s = model.snap else { return [] }
        var out: [MapPerson] = []
        for a in s.sos where a.hasFix { out.append(MapPerson(kind: "sos", name: "SOS \(a.name)", lat: a.lat, lon: a.lon)) }
        for f in s.friends { out.append(MapPerson(kind: "friend", name: f.name, lat: f.lat, lon: f.lon)) }
        if s.location.hasFix { out.append(MapPerson(kind: "me", name: "You", lat: s.location.lat, lon: s.location.lon)) }
        return out
    }

    private var area: MapRegion? {
        let areas = model.snap?.mapAreas ?? []
        if let p = selectedPath, let a = areas.first(where: { $0.path == p }) { return a }
        if let lat = focusLat, let lon = focusLon, let a = model.controller.mapAreaFor(lat: lat, lon: lon) { return a }
        if let s = model.snap, s.location.hasFix, let a = model.controller.mapAreaFor(lat: s.location.lat, lon: s.location.lon) { return a }
        return areas.max(by: { $0.sizeBytes < $1.sizeBytes })
    }

    var body: some View {
        Group {
            if let a = area {
                ZStack(alignment: .top) {
                    OfflineMapView(
                        area: a, people: people,
                        focus: focusLat.flatMap { lat in focusLon.map { CLLocationCoordinate2D(latitude: lat, longitude: $0) } }
                    )
                    .ignoresSafeArea(edges: .bottom)
                    if (model.snap?.mapAreas.count ?? 0) > 1 {
                        Picker("Area", selection: Binding(get: { a.path }, set: { selectedPath = $0 })) {
                            ForEach(model.snap?.mapAreas ?? [], id: \.path) { r in Text(r.name.replacingOccurrences(of: "_", with: " ")).tag(r.path) }
                        }
                        .pickerStyle(.menu)
                        .padding(6)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.top, 8)
                    }
                }
                .navigationTitle(a.name.replacingOccurrences(of: "_", with: " "))
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text("No offline map yet").font(.headline)
                    Text("Download the area around you once (needs internet), then the map works anywhere without it. You can also import a .pmtiles map file.")
                        .foregroundStyle(.secondary)
                    NavigationLink("Get a map area") { MapAreasScreen() }.buttonStyle(.borderedProminent)
                }
                .padding(24)
                .navigationTitle("Offline map")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { NavigationLink("Map areas") { MapAreasScreen() } }
        }
        .onAppear { model.setMapOpen(true) }
        .onDisappear { model.setMapOpen(false) }
    }
}

struct MapAreasScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var coords = ""
    @State private var radius = 5.0
    @State private var name = ""
    @State private var importing = false

    var body: some View {
        let d = model.snap?.mapDownload
        List {
            Section {
                TextField("Centre (latitude, longitude)", text: $coords)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                Picker("Radius", selection: $radius) {
                    ForEach([2.0, 5.0, 15.0, 30.0], id: \.self) { r in Text("\(Int(r)) km").tag(r) }
                }
                .pickerStyle(.segmented)
                TextField("Name (optional)", text: $name)
                switch d?.state ?? "IDLE" {
                case "PLANNING":
                    ProgressView("Looking up the newest map…")
                case "PLANNED":
                    Text("\(d!.tiles) tiles · \(String(format: "%.1f", Double(d!.bytes) / 1_048_576)) MB · detail up to zoom \(d!.maxZoom)")
                    HStack {
                        Button("Download") { model.controller.startMapDownload() }.buttonStyle(.borderedProminent)
                        Button("Cancel") { model.controller.cancelMapDownload() }.buttonStyle(.bordered)
                    }
                case "DOWNLOADING":
                    ProgressView(value: d!.total == 0 ? 1 : Double(d!.done) / Double(d!.total))
                    HStack {
                        Text(String(format: "%.1f / %.1f MB", Double(d!.done) / 1_048_576, Double(d!.total) / 1_048_576)).font(.caption)
                        Spacer()
                        Button("Cancel") { model.controller.cancelMapDownload() }
                    }
                default:
                    if let m = d?.message, !m.isEmpty { Text(m).foregroundColor(d?.state == "FAILED" ? .orange : .green) }
                    Button("Check size") { plan() }
                }
            } header: {
                Text("Download an area")
            } footer: {
                Text("Needs internet once. Map data © OpenStreetMap contributors, served by Protomaps.")
            }
            Section("On this phone") {
                let areas = model.snap?.mapAreas ?? []
                if areas.isEmpty { Text("No map areas yet.").foregroundStyle(.secondary) }
                ForEach(areas, id: \.path) { r in
                    VStack(alignment: .leading) {
                        Text(r.name.replacingOccurrences(of: "_", with: " "))
                        Text(String(format: "%.1f MB · zoom %d–%d", Double(r.sizeBytes) / 1_048_576, r.minZoom, r.maxZoom))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .swipeActions { Button(role: .destructive) { model.controller.deleteMapArea(path: r.path) } label: { Text("Delete") } }
                }
                Button("Import a .pmtiles file") { importing = true }
            }
        }
        .navigationTitle("Map areas")
        .onAppear {
            model.setMapOpen(true)
            if coords.isEmpty, let l = model.snap?.location, l.hasFix { coords = String(format: "%.5f, %.5f", l.lat, l.lon) }
        }
        .onDisappear { model.setMapOpen(false) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            guard case .success(let url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            let msg = model.controller.importMapArea(sourcePath: url.path, name: url.deletingPathExtension().lastPathComponent)
            model.show(msg)
        }
    }

    private func plan() {
        let parts = coords.split(whereSeparator: { $0 == "," || $0 == " " }).compactMap { Double($0) }
        guard parts.count == 2, abs(parts[0]) <= 85, abs(parts[1]) <= 180 else {
            model.show("Enter the centre as latitude, longitude")
            return
        }
        model.controller.planMapArea(name: name.isEmpty ? "Area \(Int(radius)) km" : name, lat: parts[0], lon: parts[1], radiusKm: radius)
    }
}
