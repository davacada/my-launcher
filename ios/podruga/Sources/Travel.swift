import CoreLocation
import MapKit
import SwiftUI

struct Way: Identifiable, Hashable {
    let id: String          // bus, bike, car, taxi, walk
    let icon: String
    let name: String
    let minutes: Int
    let cost: Double
    let costText: String
    let note: String
    var best = false
}

@MainActor
final class Travel: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = Travel()
    private let lm = CLLocationManager()
    private var waiters: [CheckedContinuation<CLLocation?, Never>] = []
    var last: CLLocation?
    var demo = false

    override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func here() async -> CLLocation? {
        if demo { return CLLocation(latitude: 46.0500, longitude: 14.5030) }
        if let l = last, -l.timestamp.timeIntervalSinceNow < 300 { return l }
        let status = lm.authorizationStatus
        if status == .denied || status == .restricted { return nil }
        if status == .notDetermined { lm.requestWhenInUseAuthorization() }
        return await withCheckedContinuation { c in
            waiters.append(c)
            lm.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in self?.finish(nil) }
        }
    }

    private func finish(_ l: CLLocation?) {
        if let l { last = l }
        let w = waiters
        waiters = []
        for c in w { c.resume(returning: l ?? last) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let l = locations.last
        Task { @MainActor in self.finish(l) }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.finish(nil) }
    }
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        Task { @MainActor in
            if s == .authorizedWhenInUse || s == .authorizedAlways, !self.waiters.isEmpty { self.lm.requestLocation() }
        }
    }

    /// Find a place by name near where she is.
    func resolve(_ query: String) async -> MKMapItem? {
        if demo {
            let pm = MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: 46.0537, longitude: 14.5086))
            let it = MKMapItem(placemark: pm); it.name = query
            return it
        }
        let r = MKLocalSearch.Request()
        r.naturalLanguageQuery = query
        if let h = await here() {
            r.region = MKCoordinateRegion(center: h.coordinate, latitudinalMeters: 30000, longitudinalMeters: 30000)
        }
        return try? await MKLocalSearch(request: r).start().mapItems.first
    }

    private func eta(_ to: MKMapItem, _ type: MKDirectionsTransportType, arrive: Date?) async -> (Int, Double)? {
        let r = MKDirections.Request()
        r.source = MKMapItem.forCurrentLocation()
        if let h = await here() { r.source = MKMapItem(placemark: MKPlacemark(coordinate: h.coordinate)) }
        r.destination = to
        r.transportType = type
        if let a = arrive, a > Date() { r.arrivalDate = a }
        guard let e = try? await MKDirections(request: r).calculateETA() else { return nil }
        return (Int((e.expectedTravelTime / 60).rounded()), e.distance)
    }

    func ways(to dest: MKMapItem, arrive: Date?, stayMinutes: Int) async -> [Way] {
        func leave(_ m: Int) -> String {
            guard let a = arrive else { return "" }
            return tr("leave ", "выйти в ") + Fmt.time(a.addingTimeInterval(-Double(m + 5) * 60))
        }
        if demo {
            var w = [
                Way(id: "bus", icon: "bus", name: tr("Bus 6 + walk", "Автобус 6 + пешком"), minutes: 25, cost: 1.3, costText: "€1.30",
                    note: leave(25) + " · Urbana"),
                Way(id: "bike", icon: "bike", name: "Bicikelj", minutes: 15, cost: 0, costText: "€0",
                    note: tr("first hour free", "первый час бесплатно")),
                Way(id: "car", icon: "car", name: tr("Car", "Машина"), minutes: 12, cost: 3.4, costText: "€3.40",
                    note: tr("petrol €0.80 + parking €2.60", "бензин €0.80 + парковка €2.60")),
                Way(id: "taxi", icon: "taxi", name: tr("Taxi", "Такси"), minutes: 10, cost: 9, costText: "≈ €9", note: tr("Bolt / Uber estimate", "Bolt / Uber, примерно")),
                Way(id: "walk", icon: "walk", name: tr("Walk", "Пешком"), minutes: 38, cost: 0, costText: "€0", note: "2.9 km"),
            ]
            w[0].best = true
            return w
        }
        async let walkE = eta(dest, .walking, arrive: arrive)
        async let carE = eta(dest, .automobile, arrive: arrive)
        async let busE = eta(dest, .transit, arrive: arrive)
        let (walk, car, bus) = await (walkE, carE, busE)
        var out: [Way] = []
        if let b = bus {
            out.append(Way(id: "bus", icon: "bus", name: tr("Bus", "Автобус"), minutes: b.0, cost: Pref.busFare,
                           costText: Fmt.money(Pref.busFare), note: [leave(b.0), "Urbana"].filter { !$0.isEmpty }.joined(separator: " · ")))
        }
        if let w = walk {
            let km = w.1 / 1000
            let bikeMin = max(3, Int((w.1 / 4.2 / 60).rounded()))
            out.append(Way(id: "bike", icon: "bike", name: "Bicikelj", minutes: bikeMin, cost: 0, costText: "€0",
                           note: [leave(bikeMin), tr("first hour free", "первый час бесплатно")].filter { !$0.isEmpty }.joined(separator: " · ")))
            out.append(Way(id: "walk", icon: "walk", name: tr("Walk", "Пешком"), minutes: w.0, cost: 0, costText: "€0",
                           note: [leave(w.0), String(format: "%.1f km", km)].filter { !$0.isEmpty }.joined(separator: " · ")))
        }
        if let c = car {
            let km = c.1 / 1000
            let fuel = km * Pref.consumption / 100 * Pref.fuelPrice
            let park = Double(Int(ceil(Double(stayMinutes + 10) / 60))) * Pref.parkingHour
            out.append(Way(id: "car", icon: "car", name: tr("Car", "Машина"), minutes: c.0, cost: fuel + park, costText: Fmt.money(fuel + park),
                           note: tr("petrol ", "бензин ") + Fmt.money(fuel) + tr(" + parking ", " + парковка ") + Fmt.money(park)))
            let taxi = 2.0 + 1.3 * km
            out.append(Way(id: "taxi", icon: "taxi", name: tr("Taxi", "Такси"), minutes: max(1, c.0 - 2), cost: taxi,
                           costText: "≈ " + Fmt.money(taxi).replacingOccurrences(of: ".00", with: ""), note: tr("Bolt / Uber estimate", "Bolt / Uber, примерно")))
        }
        // best: money plus time (time counts ~€8/h), long walks count extra
        if let i = out.indices.min(by: { score(out[$0]) < score(out[$1]) }) { out[i].best = true }
        let order = ["bus", "bike", "car", "taxi", "walk"]
        return out.sorted { a, b in
            if a.best != b.best { return a.best }
            return (order.firstIndex(of: a.id) ?? 9) < (order.firstIndex(of: b.id) ?? 9)
        }
    }

    private func score(_ w: Way) -> Double {
        var s = w.cost + Double(w.minutes) * 0.13
        if w.id == "walk", w.minutes > 25 { s += 4 }
        if w.id == "bike", w.minutes > 30 { s += 3 }
        return s
    }
}
