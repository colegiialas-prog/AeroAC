package dev.aeroac.manager.init.stop;

import dev.aeroac.manager.init.Initable;

public interface StoppableInitable extends Initable {
    void stop();
}
