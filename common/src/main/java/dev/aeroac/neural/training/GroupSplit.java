package dev.aeroac.neural.training;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Leakage-safe folds (ml/aeroml/dataset/splits.py). Whole groups — connected components over
 * session and player — go to one fold; windows of one player never sit in two folds. With an
 * unknown-client holdout, every window of a held-out cheat client (and every group that touched it)
 * goes to test, so test measures a client the model never saw.
 */
public final class GroupSplit {
    public static final List<String> FOLDS = List.of("train", "validation", "calibration", "test");
    static final Map<String, Double> FRACTIONS = fractions();

    final Map<String, int[]> folds;
    final Map<String, Object> manifest;

    private GroupSplit(Map<String, int[]> folds, Map<String, Object> manifest) {
        this.folds = folds;
        this.manifest = manifest;
    }

    public int[] fold(String name) { return folds.get(name); }

    public Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String fold : FOLDS) sizes.put(fold, folds.get(fold).length);
        return sizes;
    }

    private static Map<String, Double> fractions() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("train", 0.6);
        map.put("validation", 0.15);
        map.put("calibration", 0.1);
        map.put("test", 0.15);
        return Collections.unmodifiableMap(map);
    }

    /** Group key per window: the first session id of its connected component over session + player. */
    static String[] groupKeys(WindowSet windows, int[] rows) {
        int n = rows.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (String name : List.of("session", "player")) {
            Map<String, Integer> seen = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String value = windows.attribute(name, rows[i]);
                Integer first = seen.putIfAbsent(value, i);
                if (first != null) {
                    int left = find(parent, i), right = find(parent, first);
                    parent[Math.max(left, right)] = Math.min(left, right);
                }
            }
        }
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = windows.attribute("session", rows[find(parent, i)]);
        return keys;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Assigns whole groups to folds, greedily balancing window count and label mix. */
    static GroupSplit group(WindowSet windows, int[] rows, long seed) {
        String[] keys = groupKeys(windows, rows);
        Map<String, List<Integer>> members = new LinkedHashMap<>();
        for (int i = 0; i < rows.length; i++) members.computeIfAbsent(keys[i], key -> new ArrayList<>()).add(i);
        if (members.size() < FRACTIONS.size()) {
            throw new TrainingException("Игроков слишком мало: " + members.size() + " независимых групп не хватает на "
                    + FRACTIONS.size() + " выборки (обучение, проверка, калибровка, тест). Запишите больше разных игроков.");
        }
        List<String> order = new ArrayList<>(members.keySet());
        order.sort(Comparator.comparingInt((String key) -> -members.get(key).size()).thenComparing(key -> key));
        Collections.shuffle(order, new Random(seed));
        order.sort(Comparator.comparingInt(key -> -members.get(key).size()));

        int total = rows.length, cheatTotal = 0;
        for (int row : rows) cheatTotal += windows.label(row);
        int[] totals = {total, cheatTotal, total - cheatTotal};
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String fold : FRACTIONS.keySet()) counts.put(fold, new int[3]);
        Map<String, String> assignment = new HashMap<>();
        List<String> seeded = new ArrayList<>(FRACTIONS.keySet());
        seeded.sort(Comparator.comparingDouble((String fold) -> -FRACTIONS.get(fold)).thenComparing(fold -> fold));
        for (int i = 0; i < seeded.size(); i++) assign(windows, rows, members.get(order.get(i)), seeded.get(i), counts);
        for (int i = 0; i < seeded.size(); i++) assignment.put(order.get(i), seeded.get(i));
        for (int i = seeded.size(); i < order.size(); i++) {
            String key = order.get(i);
            String best = null;
            double bestScore = 0;
            for (Map.Entry<String, Double> fold : FRACTIONS.entrySet()) {
                double score = 0;
                for (int metric = 0; metric < 3; metric++) {
                    if (totals[metric] == 0) continue;
                    score += (fold.getValue() * totals[metric] - counts.get(fold.getKey())[metric]) / totals[metric];
                }
                if (best == null || score > bestScore) { best = fold.getKey(); bestScore = score; }
            }
            assignment.put(key, best);
            assign(windows, rows, members.get(key), best, counts);
        }
        Map<String, int[]> folds = new LinkedHashMap<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String fold : FOLDS) {
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < rows.length; i++) if (fold.equals(assignment.get(keys[i]))) chosen.add(rows[i]);
            folds.put(fold, chosen.stream().mapToInt(Integer::intValue).sorted().toArray());
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, String> entry : assignment.entrySet()) if (fold.equals(entry.getValue())) names.add(entry.getKey());
            Collections.sort(names);
            groups.put(fold, names);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("strategy", "group");
        manifest.put("groupBy", List.of("player"));
        manifest.put("fractions", FRACTIONS);
        manifest.put("seed", seed);
        manifest.put("groups", groups);
        GroupSplit split = new GroupSplit(folds, manifest);
        manifest.put("sizes", split.sizes());
        return split;
    }

    private static void assign(WindowSet windows, int[] rows, List<Integer> member, String fold, Map<String, int[]> counts) {
        int cheat = 0;
        for (int position : member) cheat += windows.label(rows[position]);
        int[] count = counts.get(fold);
        count[0] += member.size();
        count[1] += cheat;
        count[2] += member.size() - cheat;
    }

    /** The default split of a training run: a precommitted unknown-client holdout when there are two cheat clients or more. */
    public static GroupSplit make(WindowSet windows, long seed, List<String> holdoutClients, List<String> log) {
        int[] all = new int[windows.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        TreeMap<String, Boolean> cheatClients = new TreeMap<>();
        for (TrainingSession session : windows.sessions) {
            if (session.cheat() && !session.staffReview()) cheatClients.put(session.client().trim().toLowerCase(Locale.ROOT), true);
        }
        List<String> clients = new ArrayList<>(cheatClients.keySet());
        List<String> holdout = new ArrayList<>(holdoutClients);
        if (holdout.isEmpty() && clients.size() >= 2) holdout.add(clients.get((int) Math.floorMod(seed, clients.size())));
        if (clients.size() < 2) log.add("Проверка на незнакомом чит-клиенте невозможна: записан только один чит-клиент.");
        if (holdout.isEmpty()) return group(windows, all, seed);
        return unknownClient(windows, all, holdout, seed);
    }

    static GroupSplit unknownClient(WindowSet windows, int[] all, List<String> holdoutClients, long seed) {
        Set<String> holdout = new HashSet<>();
        for (String client : holdoutClients) holdout.add(client.trim().toLowerCase(Locale.ROOT));
        String[] keys = groupKeys(windows, all);
        Set<String> tainted = new HashSet<>();
        int direct = 0;
        for (int i = 0; i < all.length; i++) {
            if (holdout.contains(windows.attribute("client", all[i]).trim().toLowerCase(Locale.ROOT))) {
                tainted.add(keys[i]);
                direct++;
            }
        }
        List<Integer> held = new ArrayList<>(), remaining = new ArrayList<>();
        for (int i = 0; i < all.length; i++) (tainted.contains(keys[i]) ? held : remaining).add(all[i]);
        if (remaining.isEmpty()) throw new TrainingException("После отделения тестового чит-клиента не осталось данных для обучения.");
        GroupSplit inner = group(windows, remaining.stream().mapToInt(Integer::intValue).toArray(), seed);
        Map<String, int[]> folds = new LinkedHashMap<>(inner.folds);
        List<Integer> test = new ArrayList<>();
        for (int row : inner.folds.get("test")) test.add(row);
        test.addAll(held);
        folds.put("test", test.stream().mapToInt(Integer::intValue).sorted().toArray());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("strategy", "unknown-client");
        manifest.put("holdoutClients", new ArrayList<>(new java.util.TreeSet<>(holdout)));
        manifest.put("groupBy", List.of("player"));
        manifest.put("innerFractions", FRACTIONS);
        manifest.put("seed", seed);
        manifest.put("holdoutWindows", direct);
        manifest.put("windowsPulledInByGroup", held.size() - direct);
        manifest.put("groups", inner.manifest.get("groups"));
        GroupSplit split = new GroupSplit(folds, manifest);
        manifest.put("sizes", split.sizes());
        return split;
    }

    /**
     * Staff-reviewed windows go to train only; reviewed windows of a player who also sits in another
     * fold are dropped instead of leaking across the split (reviews.train_only).
     */
    int[] trainOnly(WindowSet windows) {
        Set<String> heldPlayers = new HashSet<>();
        for (String fold : List.of("validation", "calibration", "test")) {
            for (int row : folds.get(fold)) if (!windows.sessionOf(row).staffReview()) heldPlayers.add(windows.attribute("player", row));
        }
        int added = 0, dropped = 0;
        Map<String, int[]> cleaned = new LinkedHashMap<>();
        List<Integer> reviewed = new ArrayList<>();
        for (String fold : FOLDS) {
            List<Integer> keep = new ArrayList<>();
            for (int row : folds.get(fold)) {
                if (windows.sessionOf(row).staffReview()) reviewed.add(row); else keep.add(row);
            }
            cleaned.put(fold, keep.stream().mapToInt(Integer::intValue).toArray());
        }
        List<Integer> train = new ArrayList<>();
        for (int row : cleaned.get("train")) train.add(row);
        for (int row : reviewed) {
            if (heldPlayers.contains(windows.attribute("player", row))) dropped++;
            else { train.add(row); added++; }
        }
        cleaned.put("train", train.stream().mapToInt(Integer::intValue).sorted().toArray());
        folds.clear();
        folds.putAll(cleaned);
        manifest.put("sizes", sizes());
        return new int[]{added, dropped};
    }

    /** Raises when a player or a session straddles two folds, or a window is used twice or not at all. */
    void verify(WindowSet windows) {
        Map<String, String> players = new HashMap<>(), sessions = new HashMap<>();
        boolean[] used = new boolean[windows.size()];
        int count = 0;
        for (String fold : FOLDS) {
            for (int row : folds.get(fold)) {
                if (used[row]) throw new IllegalStateException("window " + row + " appears in more than one fold");
                used[row] = true;
                count++;
                if (windows.sessionOf(row).staffReview() && !"train".equals(fold)) throw new IllegalStateException("staff review outside train");
                String player = windows.attribute("player", row);
                if (!windows.sessionOf(row).staffReview() && !players.computeIfAbsent(player, key -> fold).equals(fold)) {
                    throw new IllegalStateException("player " + player + " is in two folds");
                }
                String session = windows.attribute("session", row);
                if (!sessions.computeIfAbsent(session, key -> fold).equals(fold)) {
                    throw new IllegalStateException("session " + session + " is in two folds");
                }
            }
        }
        if (count > windows.size()) throw new IllegalStateException("windows assigned twice");
    }
}
