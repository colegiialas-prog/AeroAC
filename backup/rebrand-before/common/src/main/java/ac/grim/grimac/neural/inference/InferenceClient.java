package ac.grim.grimac.neural.inference;

import java.util.concurrent.CompletableFuture;

/**
 * Never call from a packet or main thread in a way that waits. The returned future completes on a
 * client-owned thread; results must be handed back through GrimPlayer.runSafely.
 */
public interface InferenceClient extends AutoCloseable {

    CompletableFuture<InferenceResponse> infer(InferenceRequest request);

    /** False while the client is shedding load; the caller must then skip the request, not block. */
    boolean admit();

    InferenceHealth health();

    /**
     * Requests currently outstanding, or -1 when the implementation does not track it.
     *
     * <p>Operational reporting only: nothing may branch on this value, and a client that cannot
     * answer says so rather than returning a zero that reads as "idle".
     */
    default int inFlight() { return -1; }

    @Override void close();
}
