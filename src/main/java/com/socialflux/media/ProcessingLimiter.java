package com.socialflux.media;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Limita quantos processamentos de mídia rodam ao mesmo tempo: decodificar uma imagem grande ocupa dezenas de MB, e muitos
 * uploads simultâneos derrubariam o serviço por falta de memória. Quem não consegue vaga no tempo de espera recebe
 * {@link MediaException.Reason#BUSY} (o serviço responde 503 e continua de pé).
 */
public class ProcessingLimiter {

    private final Semaphore permits;
    private final Duration timeout;

    public ProcessingLimiter(int maxConcurrent, Duration timeout) {
        this.permits = new Semaphore(maxConcurrent, true);
        this.timeout = timeout;
    }

    public <T> T run(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    int available() {
        return permits.availablePermits();
    }

    private static MediaException busy() {
        return new MediaException(MediaException.Reason.BUSY, "Servidor ocupado processando imagens. Tente novamente.");
    }
}
