import MapKit
import SwiftUI

struct BestWayView: View {
    let id: UUID
    let back: String
    @EnvironmentObject var store: Store
    @EnvironmentObject var cal: Cal
    @State private var loading = false

    var body: some View {
        let t = store.thing(id)
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                if let t {
                    Header(back: back, title: t.title, sub: sub(t))
                    SectionLabel(text: tr("Ways to do it", "Как это сделать")).padding(.top, 2)
                    if t.ways.isEmpty {
                        if loading {
                            HStack(spacing: 10) {
                                ProgressView().tint(C.pink)
                                Text(tr("\(appName) is thinking…", "\(appName) думает…")).font(.inter(14)).foregroundStyle(C.sub)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading).padding(16).card()
                        } else if !AI.available {
                            Text(tr("Suggestions use Apple's on-phone AI. It works on iPhone 15 Pro, iPhone 16 and newer with Apple Intelligence on (\(AI.status)).",
                                    "Советы работают на ИИ Apple прямо в телефоне: iPhone 15 Pro, iPhone 16 и новее, с включённым Apple Intelligence (\(AI.status))."))
                                .font(.inter(13.5)).foregroundStyle(C.sub).lineSpacing(3)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(16).card()
                        }
                    }
                    ForEach(t.ways) { w in OptionCard(w: w) }
                    if let why = t.waysWhy {
                        Text(tr("Why: ", "Почему: ") + why).font(.inter(12.5)).foregroundStyle(C.sub).lineSpacing(3)
                            .padding(.horizontal, 4).padding(.top, 6)
                    }
                    if !t.ways.isEmpty && AI.available {
                        Button { Task { await load(force: true) } } label: {
                            Text(tr("Ask again", "Спросить ещё раз")).font(.inter(13.5, .semibold)).foregroundStyle(C.pink)
                                .frame(maxWidth: .infinity).padding(.vertical, 14)
                        }.buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: the best way to do it")
        .task { await load(force: false) }
    }

    func sub(_ t: Thing) -> String {
        if let d = t.deadline { return tr("by ", "до ") + Fmt.shortDay(d) }
        if let s = t.start { return Fmt.when(s) }
        return ""
    }

    func load(force: Bool) async {
        guard let t = store.thing(id), force || t.ways.isEmpty, AI.available else { return }
        loading = true
        if let r = await AI.ways(for: t, now: cal.now), var x = store.thing(id) {
            let opts = r.0, why = r.1
            x.ways = opts
            x.waysWhy = why
            if x.note == nil, let b = opts.first(where: { $0.best }) { x.note = b.name.lowercased() + ", " + b.time }
            store.upsert(x)
        }
        loading = false
    }
}

struct OptionCard: View {
    let w: WayOption
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(w.name).font(.inter(16.5, .bold)).foregroundStyle(C.text)
                Spacer()
                if w.best {
                    Text(tr("Best for you", "Лучше всего")).font(.inter(11, .bold)).foregroundStyle(C.text)
                        .padding(.horizontal, 9).padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 10).fill(C.grad))
                }
            }
            row("coin", tr("Cost", "Цена"), w.cost)
            row("clock", tr("Your time", "Твоё время"), w.time)
            row("bolt", tr("Done by", "Готово"), w.doneBy)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(w.best ? C.soft : Color.white))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(w.best ? C.outline : C.line, lineWidth: w.best ? 2 : 1))
        .padding(.bottom, 12)
    }
    func row(_ icon: String, _ label: String, _ value: String) -> some View {
        HStack(spacing: 8) {
            Ic(name: icon, size: 16, color: C.pink)
            Text(label).font(.inter(13)).foregroundStyle(C.sub)
            Spacer()
            Text(value).font(.inter(13, .semibold)).foregroundStyle(C.text).multilineTextAlignment(.trailing)
        }
        .padding(.top, 6)
    }
}

struct GetThereView: View {
    let id: UUID
    let back: String
    @EnvironmentObject var store: Store
    @State private var ways: [Way] = []
    @State private var dest: CLLocationCoordinate2D?
    @State private var loading = true
    @State private var failed = false

    var body: some View {
        let t = store.thing(id)
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                if let t {
                    Header(back: back, title: t.title,
                           sub: [t.start.map { Fmt.when($0) }, t.place].compactMap { $0 }.joined(separator: " · "))
                    map.padding(.top, 14)
                    SectionLabel(text: tr("How to get there · leave by", "Как добраться · когда выходить"))
                    if loading {
                        ProgressView().tint(C.pink).frame(maxWidth: .infinity).padding(20)
                    } else if failed {
                        Text(tr("Couldn't find this place on the map. Allow location and check the address.",
                                "Не удалось найти место на карте. Разрешите геолокацию и проверьте адрес."))
                            .font(.inter(13.5)).foregroundStyle(C.sub).padding(16).frame(maxWidth: .infinity, alignment: .leading).card()
                    }
                    ForEach(ways) { w in
                        Button { choose(w) } label: { WayRow(w: w) }.buttonStyle(.plain)
                    }
                    if !ways.isEmpty {
                        Text(tr("Prices are estimates: bus fare, petrol and parking from Settings, taxi from a typical rate.",
                                "Цены примерные: проезд, бензин и парковка из настроек, такси по обычному тарифу."))
                            .font(.inter(12)).foregroundStyle(C.sub).padding(.horizontal, 4).padding(.top, 6)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: getting there & costs")
        .task { await load() }
    }

    var map: some View {
        Group {
            if let d = dest {
                Map(initialPosition: .region(MKCoordinateRegion(center: d, latitudinalMeters: 2500, longitudinalMeters: 2500))) {
                    Marker("", coordinate: d).tint(C.pink)
                    UserAnnotation()
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                .allowsHitTesting(false)
            } else {
                LinearGradient(colors: [Color(hex: 0xE6F6EC), Color(hex: 0xFDE9F1)], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
        }
        .frame(height: 120)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    func load() async {
        guard let t = store.thing(id), let place = t.place else { loading = false; failed = true; return }
        var item: MKMapItem?
        if let la = t.lat, let lo = t.lon {
            let it = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: la, longitude: lo)))
            it.name = place
            item = it
        } else {
            item = await Travel.shared.resolve(place)
        }
        guard let item else { loading = false; failed = true; return }
        dest = item.placemark.coordinate
        ways = await Travel.shared.ways(to: item, arrive: t.start, stayMinutes: t.minutes)
        loading = false
        failed = ways.isEmpty
        if var x = store.thing(id) {
            x.lat = dest?.latitude; x.lon = dest?.longitude
            if x.travelMinutes == nil, let b = ways.first(where: { $0.best }) {
                x.travelMinutes = b.minutes
                x.travelNote = b.name + (b.note.isEmpty ? "" : " · " + b.note)
            }
            store.upsert(x)
        }
    }

    func choose(_ w: Way) {
        ways = ways.map { var x = $0; x.best = x.id == w.id; return x }
        if var x = store.thing(id) {
            x.travelMinutes = w.minutes
            x.travelNote = w.name + (w.note.isEmpty ? "" : " · " + w.note)
            store.upsert(x)
            Task { await Notifier.shared.reschedule() }
        }
    }
}

struct WayRow: View {
    let w: Way
    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(w.best ? AnyShapeStyle(C.grad) : AnyShapeStyle(C.tint)).frame(width: 40, height: 40)
                Ic(name: w.icon, size: 21)
            }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(w.name).font(.inter(15, .bold)).foregroundStyle(C.text)
                    if w.best {
                        Text(tr("Best", "Лучше")).font(.inter(10.5, .bold)).foregroundStyle(C.text)
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(RoundedRectangle(cornerRadius: 8).fill(C.grad))
                    }
                }
                Text(w.note).font(.inter(12)).foregroundStyle(C.sub).lineLimit(1)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 1) {
                Text(w.costText).font(.inter(15, .bold)).foregroundStyle(C.text)
                Text(Fmt.dur(w.minutes)).font(.inter(12)).foregroundStyle(C.sub)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 13)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(w.best ? C.soft : Color.white))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(w.best ? C.outline : Color.clear, lineWidth: 2))
        .shadow(color: w.best ? .clear : C.text.opacity(0.05), radius: 7, y: 4)
        .padding(.bottom, 8)
    }
}
