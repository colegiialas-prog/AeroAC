package dev.aeroac.neural.training;

/** A training run that cannot go on, with an operator-facing reason. */
public final class TrainingException extends RuntimeException {
    public TrainingException(String message) {
        super(message);
    }
}
