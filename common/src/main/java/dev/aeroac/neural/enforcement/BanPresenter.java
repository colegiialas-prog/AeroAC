package dev.aeroac.neural.enforcement;

/**
 * The platform's send-off, and the seam that keeps the decision away from it.
 *
 * <p>Deciding is arithmetic on a published snapshot and lives in common. Levitating somebody,
 * emptying their inventory into the world and playing a sound is Bukkit, per version, and lives on
 * the platform. Keeping them apart means the decision is testable without a server and the
 * animation cannot accidentally become part of why somebody was banned.
 *
 * <p>An implementation must call {@code onFinished} exactly once — on completion, on a failure, on
 * the player disconnecting mid-flight, on the plugin stopping. The ban runs from that callback, so a
 * presenter that forgets it is a presenter that quietly cancels bans, and one that calls it twice
 * bans twice.
 */
public interface BanPresenter {

    /** Nothing to draw: the ban runs immediately. */
    BanPresenter IMMEDIATE = new BanPresenter() {
        @Override public boolean available() { return false; }
        @Override public void present(BanDecision decision, BanPolicy.Animation animation, boolean preview,
                                      Runnable onFinished) {
            onFinished.run();
        }
        @Override public void cancelAll() { }
    };

    boolean available();

    /**
     * Plays the send-off for one player and then hands control back.
     *
     * @param preview    true when nobody is being banned: the show plays, but the player's inventory
     *                   is left exactly as it was and nothing they own ends up on the ground
     * @param onFinished run once, on whichever thread the platform finishes on
     */
    void present(BanDecision decision, BanPolicy.Animation animation, boolean preview, Runnable onFinished);

    /** Releases anyone still mid-animation. Called on reload and on disable; must never strand. */
    void cancelAll();
}
