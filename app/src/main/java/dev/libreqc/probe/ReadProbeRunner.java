package dev.libreqc.probe;

import dev.libreqc.bmap.BmapDecodingException;
import dev.libreqc.bmap.BmapFrame;
import dev.libreqc.bmap.BmapOperator;
import dev.libreqc.bmap.BmapStreamDecoder;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class ReadProbeRunner {
    public enum Outcome {
        SUPPORTED,
        UNSUPPORTED,
        TIMED_OUT,
        MALFORMED,
        UNEXPECTED
    }

    public interface Listener {
        void onReceiveChunk(byte[] bytes);

        void onFrame(BmapFrame frame, boolean matchesCurrentProbe);
    }

    public record Result(
            ReadProbe probe,
            Outcome outcome,
            BmapFrame response,
            List<BmapFrame> unrelatedFrames,
            BmapDecodingException malformed,
            long elapsedMs) {
    }

    private final BmapStreamDecoder decoder = new BmapStreamDecoder();
    private final Listener listener;

    public ReadProbeRunner(Listener listener) {
        this.listener = listener;
    }

    public Result exchange(
            InputStream input,
            OutputStream output,
            ReadProbe probe,
            long timeoutMs) throws Exception {
        output.write(probe.packet());
        output.flush();

        long startedAt = System.currentTimeMillis();
        List<BmapFrame> unrelated = new ArrayList<>();
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            int available = input.available();
            if (available == 0) {
                Thread.sleep(20);
                continue;
            }

            byte[] chunk = new byte[Math.min(available, 1024)];
            int count = input.read(chunk);
            if (count <= 0) {
                continue;
            }
            if (count != chunk.length) {
                chunk = java.util.Arrays.copyOf(chunk, count);
            }
            listener.onReceiveChunk(chunk);

            BmapFrame matching = null;
            for (BmapFrame frame : decoder.feed(chunk)) {
                boolean accepts = probe.accepts(frame);
                boolean related = probe.isRelated(frame);
                listener.onFrame(frame, related);
                if (accepts && matching == null) {
                    matching = frame;
                } else if (!related) {
                    unrelated.add(frame);
                }
            }
            if (matching != null) {
                Outcome outcome = matching.getOperator() == BmapOperator.Error
                        ? Outcome.UNSUPPORTED
                        : Outcome.SUPPORTED;
                return new Result(
                        probe,
                        outcome,
                        matching,
                        List.copyOf(unrelated),
                        null,
                        System.currentTimeMillis() - startedAt);
            }
        }

        BmapDecodingException malformed = decoder.discardPending();
        Outcome outcome;
        if (malformed != null) {
            outcome = Outcome.MALFORMED;
        } else if (!unrelated.isEmpty()) {
            outcome = Outcome.UNEXPECTED;
        } else {
            outcome = Outcome.TIMED_OUT;
        }
        return new Result(
                probe,
                outcome,
                null,
                List.copyOf(unrelated),
                malformed,
                System.currentTimeMillis() - startedAt);
    }

    public void drain(InputStream input, long durationMs) throws Exception {
        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() - startedAt < durationMs) {
            int available = input.available();
            if (available == 0) {
                Thread.sleep(20);
                continue;
            }
            byte[] chunk = new byte[Math.min(available, 1024)];
            int count = input.read(chunk);
            if (count <= 0) {
                continue;
            }
            if (count != chunk.length) {
                chunk = java.util.Arrays.copyOf(chunk, count);
            }
            listener.onReceiveChunk(chunk);
            for (BmapFrame frame : decoder.feed(chunk)) {
                listener.onFrame(frame, false);
            }
        }
        decoder.discardPending();
    }
}
