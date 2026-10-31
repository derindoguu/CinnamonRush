package bakery;

import java.util.*;

/**
 * All game rules for Cinnamon Rush live here. The browser only renders the state
 * this class produces and sends the player's clicks back as actions.
 *
 * Production line: mixer -> proofer -> rolling table (filling) -> oven -> icing station
 * -> packed into an order. Each tray holds 12 rolls. The shift ends when the truck arrives.
 */
public final class Game {

    enum Phase { BRIEFING, SHIFT, RESULTS }

    enum Stage { DOUGH, PROOFED, ROLLED, BAKED, ICED }

    record Result(boolean ok, String msg) {
        static Result ok(String msg) { return new Result(true, msg); }
        static Result fail(String msg) { return new Result(false, msg); }
    }

    record Upgrade(String id, String name, String desc, int cost, int max) {}

    static final int TRAY_SIZE = 12;
    static final long MIX_MS = 6_000, PROOF_MS = 9_000, OVERPROOF_MS = 14_000, ROLL_MS = 5_000,
            BAKE_MS = 12_000, BURN_MS = 9_000, ICE_MS = 4_000;
    static final double BASE_PRICE = 2.0, SOFT_PAY = 0.6, RUSH_MULT = 1.5, COMPLETE_BONUS = 0.2;

    static final List<String> FILLINGS = List.of("classic", "pecan", "apple", "chocolate");
    static final List<String> ICINGS = List.of("none", "glaze", "cream", "maple");
    static final Map<String, Double> PRICE_BONUS = Map.of(
            "classic", 0.0, "pecan", 0.6, "apple", 0.5, "chocolate", 0.8,
            "none", 0.0, "glaze", 0.3, "cream", 0.6, "maple", 0.5);
    static final Map<String, String> NAMES = Map.of(
            "classic", "Classic", "pecan", "Pecan", "apple", "Apple", "chocolate", "Chocolate",
            "none", "plain", "glaze", "glazed", "cream", "cream cheese", "maple", "maple");

    static final List<String> CLIENTS = List.of(
            "Northgate Hospital Cafeteria", "Riverside Elementary", "The Grand Halvorsen Hotel",
            "City Hall Breakfast Meeting", "Copperline Coffee Co.", "Westbrook University Dining",
            "Harbor Street Diner", "Pinecrest Retirement Home", "Metro Transit Depot",
            "Saturday Farmers Market", "Bluebird Wedding Brunch", "Ironworks Tech Campus",
            "St. Agnes Charity Bake Sale", "Summit Ski Lodge", "Lakeside Conference Center");

    static final List<Upgrade> UPGRADES = List.of(
            new Upgrade("oven", "Extra Oven", "Another deck oven on the make line.", 160, 2),
            new Upgrade("proofer", "Proofer Slot", "Room for one more tray in the proofing cabinet.", 80, 2),
            new Upgrade("table", "Rolling Table", "More bench space to roll and fill.", 110, 1),
            new Upgrade("icer", "Icing Station", "Ice two trays at once.", 90, 1),
            new Upgrade("turbo", "Turbo Mixers", "Mixing takes 40% less time.", 90, 1),
            new Upgrade("convection", "Convection Fans", "Baking takes 25% less time.", 150, 1),
            new Upgrade("pickup", "Later Pickup", "The truck arrives 20 seconds later.", 120, 2));

    static final class Tray {
        final int id;
        Stage stage = Stage.DOUGH;
        String filling;
        String icing = "none";
        boolean overproofed, burnt;
        int count = TRAY_SIZE;

        Tray(int id) { this.id = id; }
    }

    static final class Station {
        final String id, kind, label;
        Tray tray;
        boolean working;
        long start, duration, readyAt;
        String choice;

        Station(String id, String kind, String label) {
            this.id = id;
            this.kind = kind;
            this.label = label;
        }
    }

    static final class Line {
        final String filling, icing;
        final int qty;
        int packedGood, packedSoft;

        Line(String filling, String icing, int qty) {
            this.filling = filling;
            this.icing = icing;
            this.qty = qty;
        }

        int packed() { return packedGood + packedSoft; }

        double price() { return BASE_PRICE + PRICE_BONUS.get(filling) + PRICE_BONUS.get(icing); }
    }

    static final class Order {
        final int id;
        final String client;
        final boolean rush;
        final List<Line> lines = new ArrayList<>();

        Order(int id, String client, boolean rush) {
            this.id = id;
            this.client = client;
            this.rush = rush;
        }

        boolean complete() { return lines.stream().allMatch(l -> l.packed() >= l.qty); }
    }

    private final Random rng = new Random();
    private final Map<String, Integer> owned = new HashMap<>();
    private final List<Station> stations = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final Deque<Map<String, Object>> notices = new ArrayDeque<>();

    private Phase phase;
    private int day = 1, nextId = 1, noticeSeq, version, burntToday, softToday;
    private double cash, totalEarned;
    private long clock, shiftLen, lastReal = System.currentTimeMillis();
    private boolean paused, rushSpawned, truckWarned;
    private String rushClient;
    private Tray hand;
    private Map<String, Object> results;

    Game() { setupDay(); }

    // ---------------------------------------------------------------- day setup

    private void setupDay() {
        phase = Phase.BRIEFING;
        paused = rushSpawned = truckWarned = false;
        hand = null;
        results = null;
        clock = 0;
        burntToday = softToday = 0;
        shiftLen = (240 + Math.min(day - 1, 6) * 15 + 20L * level("pickup")) * 1000;

        stations.clear();
        addStations("mixer", "Mixer", 2);
        addStations("proofer", "Proofer", 3 + level("proofer"));
        addStations("table", "Table", 2 + level("table"));
        addStations("oven", "Oven", 2 + level("oven"));
        addStations("icer", "Icing", 1 + level("icer"));
        addStations("shelf", "Rack", 4);

        orders.clear();
        List<String> clients = new ArrayList<>(CLIENTS);
        Collections.shuffle(clients, rng);
        int count = Math.min(2 + (day - 1) / 2, 5);
        int halfTrays = (72 + 24 * (day - 1)) / 6;
        for (int i = 0; i < count; i++) {
            int share = halfTrays / count + (i < halfTrays % count ? 1 : 0);
            orders.add(makeOrder(clients.get(i), share, false));
        }
        rushClient = clients.get(count);
    }

    private void addStations(String kind, String label, int n) {
        for (int i = 1; i <= n; i++) stations.add(new Station(kind + i, kind, label + " " + i));
    }

    /** Builds an order of {@code halfTrays * 6} rolls split across up to three distinct recipes. */
    private Order makeOrder(String client, int halfTrays, boolean rush) {
        Order o = new Order(nextId++, client, rush);
        List<String[]> combos = new ArrayList<>();
        for (String f : fillingsUnlocked()) for (String i : icingsUnlocked()) combos.add(new String[] {f, i});
        Collections.shuffle(combos, rng);
        int lines = Math.min(halfTrays, 1 + rng.nextInt(rush ? 2 : 3));
        int[] split = new int[lines];
        Arrays.fill(split, 1);
        for (int left = halfTrays - lines; left > 0; left--) split[rng.nextInt(lines)]++;
        for (int k = 0; k < lines; k++) o.lines.add(new Line(combos.get(k)[0], combos.get(k)[1], split[k] * 6));
        return o;
    }

    private List<String> fillingsUnlocked() { return FILLINGS.subList(0, day >= 4 ? 4 : day >= 2 ? 3 : 2); }

    private List<String> icingsUnlocked() { return ICINGS.subList(0, day >= 3 ? 4 : 3); }

    private int level(String upgrade) { return owned.getOrDefault(upgrade, 0); }

    private int upgradeCost(Upgrade u) { return u.cost() + level(u.id()) * u.cost() / 2; }

    // ---------------------------------------------------------------- simulation

    /** Advances the game clock to real time and resolves any timers that finished. */
    synchronized void tick() {
        long now = System.currentTimeMillis();
        if (phase == Phase.SHIFT && !paused) clock += now - lastReal;
        lastReal = now;
        if (phase != Phase.SHIFT) return;

        for (Station s : stations) process(s);
        if (!rushSpawned && clock >= shiftLen * 45 / 100) {
            rushSpawned = true;
            Order rush = makeOrder(rushClient, 2 + rng.nextInt(3), true);
            orders.add(rush);
            notice("RUSH ORDER from " + rush.client + " - pays 50% extra!", "rush");
        }
        if (!truckWarned && shiftLen - clock <= 30_000) {
            truckWarned = true;
            notice("The delivery truck is 30 seconds out!", "warn");
        }
        if (clock >= shiftLen) endShift();
    }

    private void process(Station s) {
        if (s.working && clock - s.start >= s.duration) {
            s.working = false;
            s.readyAt = s.start + s.duration;
            switch (s.kind) {
                case "mixer" -> s.tray = new Tray(nextId++);
                case "proofer" -> s.tray.stage = Stage.PROOFED;
                case "table" -> {
                    s.tray.stage = Stage.ROLLED;
                    s.tray.filling = s.choice;
                }
                case "oven" -> s.tray.stage = Stage.BAKED;
                case "icer" -> {
                    s.tray.stage = Stage.ICED;
                    s.tray.icing = s.choice;
                }
                default -> {}
            }
        }
        if (s.working || s.tray == null) return;
        if (s.kind.equals("proofer") && !s.tray.overproofed && clock - s.readyAt >= OVERPROOF_MS) {
            s.tray.overproofed = true;
            softToday++;
            notice(s.label + ": dough over-proofed. Those rolls will bake flat (60% pay).", "bad");
        }
        if (s.kind.equals("oven") && !s.tray.burnt && clock - s.readyAt >= BURN_MS) {
            s.tray.burnt = true;
            burntToday++;
            notice(s.label + ": a tray just burnt!", "bad");
        }
    }

    private void endShift() {
        List<Object> rows = new ArrayList<>();
        double revenue = 0, bonus = 0;
        int delivered = 0, requested = 0;
        for (Order o : orders) {
            double value = 0;
            int got = 0, want = 0;
            for (Line l : o.lines) {
                double p = l.price() * (o.rush ? RUSH_MULT : 1);
                value += p * (l.packedGood + l.packedSoft * SOFT_PAY);
                got += l.packed();
                want += l.qty;
            }
            double b = o.complete() ? value * COMPLETE_BONUS : 0;
            revenue += value;
            bonus += b;
            delivered += got;
            requested += want;
            rows.add(map("client", o.client, "rush", o.rush, "delivered", got, "requested", want,
                    "pay", round2(value), "bonus", round2(b)));
        }
        double pct = requested == 0 ? 0 : (double) delivered / requested;
        int stars = pct >= 0.95 ? 3 : pct >= 0.7 ? 2 : pct >= 0.4 ? 1 : 0;
        cash += revenue + bonus;
        totalEarned += revenue + bonus;
        results = map("orders", rows, "revenue", round2(revenue), "bonus", round2(bonus),
                "delivered", delivered, "requested", requested, "stars", stars,
                "burnt", burntToday, "soft", softToday);
        phase = Phase.RESULTS;
        paused = false;
        hand = null;
        notice("The truck has left the dock.", "info");
    }

    // ---------------------------------------------------------------- player actions

    synchronized Result act(String type, Map<String, String> p) {
        tick();
        version++;
        return switch (type) {
            case "start" -> {
                if (phase != Phase.BRIEFING) yield Result.fail("The shift already started.");
                phase = Phase.SHIFT;
                lastReal = System.currentTimeMillis();
                yield Result.ok(null);
            }
            case "pause" -> {
                if (phase != Phase.SHIFT) yield Result.fail("Nothing to pause.");
                paused = !paused;
                yield Result.ok(null);
            }
            case "click" -> shiftOpen() ? click(p.get("station"), p.get("choice")) : closed();
            case "pack" -> shiftOpen() ? pack(parseInt(p.get("order"))) : closed();
            case "trash" -> {
                if (!shiftOpen()) yield closed();
                if (hand == null) yield Result.fail("Your hands are already empty.");
                hand = null;
                yield Result.ok("Tossed it in the bin.");
            }
            case "buy" -> buy(p.get("upgrade"));
            case "next" -> {
                if (phase != Phase.RESULTS) yield Result.fail("Finish the shift first.");
                day++;
                setupDay();
                yield Result.ok(null);
            }
            case "restart" -> {
                day = 1;
                cash = totalEarned = 0;
                owned.clear();
                setupDay();
                yield Result.ok("Fresh start. Good luck, baker!");
            }
            default -> Result.fail("Unknown action.");
        };
    }

    private boolean shiftOpen() { return phase == Phase.SHIFT && !paused; }

    private Result closed() { return Result.fail(paused ? "The game is paused." : "The kitchen is closed right now."); }

    private Result click(String id, String choice) {
        Station s = stations.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        if (s == null) return Result.fail("Unknown station.");

        if (hand == null) {
            if (s.kind.equals("mixer") && s.tray == null && !s.working) {
                begin(s, MIX_MS * (level("turbo") > 0 ? 60 : 100) / 100, null);
                return Result.ok(null);
            }
            if (s.working) return Result.fail(s.label + " is still going. Wait for it to finish.");
            if (s.tray == null) return Result.fail(s.label + " is empty.");
            hand = s.tray;
            s.tray = null;
            return Result.ok(null);
        }

        if (s.working || s.tray != null) return Result.fail(s.label + " is occupied and your hands are full.");
        String need = accepts(s.kind);
        if (need == null) return Result.fail("Your hands are full. Put the tray down somewhere first.");
        if (hand.burnt) return Result.fail("That tray is burnt. Toss it in the bin.");
        if (!need.equals("ANY") && !need.equals(hand.stage.name())) return Result.fail(wrongStation(s.kind));

        switch (s.kind) {
            case "proofer" -> begin(s, PROOF_MS, null);
            case "table" -> {
                if (choice == null || !fillingsUnlocked().contains(choice)) return Result.fail("Pick a filling first.");
                begin(s, ROLL_MS, choice);
            }
            case "oven" -> begin(s, BAKE_MS * (level("convection") > 0 ? 75 : 100) / 100, null);
            case "icer" -> {
                if (choice == null || choice.equals("none") || !icingsUnlocked().contains(choice))
                    return Result.fail("Pick an icing first.");
                begin(s, ICE_MS, choice);
            }
            default -> {} // racks just hold the tray
        }
        s.tray = hand;
        hand = null;
        return Result.ok(null);
    }

    private void begin(Station s, long duration, String choice) {
        s.working = true;
        s.start = clock;
        s.duration = duration;
        s.choice = choice;
    }

    private static String accepts(String kind) {
        return switch (kind) {
            case "proofer" -> "DOUGH";
            case "table" -> "PROOFED";
            case "oven" -> "ROLLED";
            case "icer" -> "BAKED";
            case "shelf" -> "ANY";
            default -> null;
        };
    }

    private static String wrongStation(String kind) {
        return switch (kind) {
            case "proofer" -> "The proofer takes fresh dough straight from a mixer.";
            case "table" -> "Rolling tables need proofed dough.";
            case "oven" -> "Only rolled, filled trays go in the oven.";
            case "icer" -> "Icing goes on freshly baked rolls.";
            default -> "That doesn't go there.";
        };
    }

    private Result pack(int orderId) {
        if (hand == null) return Result.fail("Pick up a tray of finished rolls first.");
        if (hand.burnt) return Result.fail("Nobody wants burnt rolls. Toss them.");
        if (hand.stage != Stage.BAKED && hand.stage != Stage.ICED) return Result.fail("Those aren't finished rolls yet.");
        Order o = orders.stream().filter(x -> x.id == orderId).findFirst().orElse(null);
        if (o == null) return Result.fail("Unknown order.");

        String icing = hand.stage == Stage.ICED ? hand.icing : "none";
        String what = NAMES.get(icing) + " " + NAMES.get(hand.filling);
        for (Line l : o.lines) {
            if (!l.filling.equals(hand.filling) || !l.icing.equals(icing) || l.packed() >= l.qty) continue;
            int n = Math.min(hand.count, l.qty - l.packed());
            if (hand.overproofed) l.packedSoft += n;
            else l.packedGood += n;
            hand.count -= n;
            if (hand.count == 0) hand = null;
            if (o.complete()) notice(o.client + " order is complete!", "good");
            return Result.ok("Boxed " + n + " " + what + " rolls for " + o.client + ".");
        }
        return Result.fail(o.client + " doesn't need any more " + what + " rolls.");
    }

    private Result buy(String id) {
        if (phase != Phase.RESULTS) return Result.fail("Upgrades are installed between shifts.");
        Upgrade u = UPGRADES.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (u == null) return Result.fail("Unknown upgrade.");
        if (level(u.id()) >= u.max()) return Result.fail("Already maxed out.");
        int cost = upgradeCost(u);
        if (cash < cost) return Result.fail("Not enough cash for " + u.name() + ".");
        cash -= cost;
        owned.merge(u.id(), 1, Integer::sum);
        return Result.ok("Installed: " + u.name() + "!");
    }

    // ---------------------------------------------------------------- state for the browser

    synchronized Map<String, Object> state() {
        List<Object> st = new ArrayList<>();
        for (Station s : stations) {
            String status;
            double progress = 0, danger = 0;
            long remaining = 0;
            if (s.working) {
                status = "working";
                progress = Math.min(1, (double) (clock - s.start) / s.duration);
                remaining = Math.max(0, s.duration - (clock - s.start));
            } else if (s.tray == null) {
                status = "empty";
            } else if (s.tray.burnt) {
                status = "ruined";
            } else {
                long limit = s.kind.equals("oven") ? BURN_MS : s.kind.equals("proofer") && !s.tray.overproofed ? OVERPROOF_MS : 0;
                if (limit > 0) danger = Math.min(1, (double) (clock - s.readyAt) / limit);
                status = danger > 0.35 ? "danger" : "ready";
            }
            st.add(map("id", s.id, "kind", s.kind, "label", s.label, "accepts", accepts(s.kind),
                    "state", status, "progress", progress, "remaining", remaining, "danger", danger,
                    "tray", tray(s.tray)));
        }

        List<Object> ord = new ArrayList<>();
        for (Order o : orders) {
            List<Object> lines = new ArrayList<>();
            for (Line l : o.lines) {
                lines.add(map("filling", l.filling, "icing", l.icing, "qty", l.qty, "packed", l.packed(),
                        "price", round2(l.price() * (o.rush ? RUSH_MULT : 1))));
            }
            ord.add(map("id", o.id, "client", o.client, "rush", o.rush, "lines", lines));
        }

        List<Object> shop = new ArrayList<>();
        for (Upgrade u : UPGRADES) {
            shop.add(map("id", u.id(), "name", u.name(), "desc", u.desc(), "cost", upgradeCost(u),
                    "level", level(u.id()), "max", u.max()));
        }

        return map("version", version, "phase", phase.name(), "day", day, "cash", round2(cash),
                "totalEarned", round2(totalEarned), "paused", paused, "clock", clock, "shiftLen", shiftLen,
                "hand", tray(hand), "stations", st, "orders", ord, "notices", new ArrayList<>(notices),
                "fillings", fillingsUnlocked(), "icings", icingsUnlocked(), "results", results, "shop", shop);
    }

    private static Map<String, Object> tray(Tray t) {
        if (t == null) return null;
        return map("id", t.id, "stage", t.stage.name(), "filling", t.filling, "icing", t.icing,
                "overproofed", t.overproofed, "burnt", t.burnt, "count", t.count);
    }

    private void notice(String text, String kind) {
        notices.addLast(map("id", ++noticeSeq, "text", text, "kind", kind));
        while (notices.size() > 20) notices.removeFirst();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
