package dev.aeroac.neural.admin;

import dev.aeroac.neural.risk.RiskState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One refresh worth of player rows, already sorted and counted.
 *
 * <p>Published as a single immutable object so a screen renders one consistent moment: a list that
 * was sorted while players were still being updated would show the same player twice and miss
 * another. Sorting is highest accumulated risk first and model output second, so the row an operator needs is
 * the first one they see, and players the model has never scored sit at the bottom rather than
 * pretending to be safe at 0%.
 */
public record AdminSnapshot(List<AdminPlayerView> players, Map<RiskState, Integer> stateCounts,
                            List<RecordingView> recordings, long builtAtMillis) {

    private static final Comparator<AdminPlayerView> BY_RISK =
            Comparator.comparingDouble(AdminPlayerView::sortKey).reversed()
                    .thenComparing(Comparator.comparingDouble((AdminPlayerView view) ->
                            view.hasPrediction() ? view.overall() : -1).reversed())
                    .thenComparing(view -> view.name() == null ? "" : view.name(), String.CASE_INSENSITIVE_ORDER);

    public AdminSnapshot {
        players = List.copyOf(players);
        stateCounts = Map.copyOf(stateCounts);
        recordings = List.copyOf(recordings);
    }

    public static final AdminSnapshot EMPTY = new AdminSnapshot(List.of(), Map.of(), List.of(), 0);

    public static AdminSnapshot of(List<AdminPlayerView> views, long builtAtMillis) {
        List<AdminPlayerView> sorted = new ArrayList<>(views);
        sorted.sort(BY_RISK);
        Map<RiskState, Integer> counts = new EnumMap<>(RiskState.class);
        List<RecordingView> recordings = new ArrayList<>();
        for (AdminPlayerView view : sorted) {
            RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
            counts.merge(state, 1, Integer::sum);
            if (view.recording() != null) recordings.add(view.recording());
        }
        return new AdminSnapshot(List.copyOf(sorted), Map.copyOf(counts), List.copyOf(recordings), builtAtMillis);
    }

    public int count(RiskState state) { return stateCounts.getOrDefault(state, 0); }

    /** Everything at or above WATCH, already in sort order. */
    public List<AdminPlayerView> suspicious() {
        List<AdminPlayerView> result = new ArrayList<>();
        for (AdminPlayerView view : players) if (view.suspicious()) result.add(view);
        return List.copyOf(result);
    }

    public AdminPlayerView find(UUID uuid) {
        for (AdminPlayerView view : players) if (view.uuid().equals(uuid)) return view;
        return null;
    }

    public AdminPlayerView findByName(String name) {
        if (name == null) return null;
        for (AdminPlayerView view : players) if (name.equalsIgnoreCase(view.name())) return view;
        return null;
    }

    /**
     * One page of a list, clamped rather than refused.
     *
     * <p>A page number that has fallen off the end is not an error worth showing an operator: the
     * list is live, and a player who logged out between two clicks must not produce an empty screen.
     */
    public static <T> Page<T> page(List<T> items, int page, int perPage) {
        int size = Math.max(1, perPage);
        int pages = Math.max(1, (items.size() + size - 1) / size);
        int index = Math.max(0, Math.min(page, pages - 1));
        int from = index * size;
        int to = Math.min(items.size(), from + size);
        return new Page<>(from >= items.size() ? List.of() : List.copyOf(items.subList(from, to)),
                index, pages, items.size());
    }

    public record Page<T>(List<T> items, int index, int pages, int total) {
        public boolean hasPrevious() { return index > 0; }
        public boolean hasNext() { return index < pages - 1; }
        public int displayIndex() { return index + 1; }
    }
}
