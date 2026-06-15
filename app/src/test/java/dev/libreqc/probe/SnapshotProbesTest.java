package dev.libreqc.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.libreqc.bmap.BmapAddress;
import dev.libreqc.bmap.BmapFrame;
import dev.libreqc.bmap.BmapOperator;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SnapshotProbesTest {
    @Test
    public void everySnapshotProbeEncodesGet() {
        for (ReadProbe probe : SnapshotProbes.BASE) {
            assertEquals(probe.name(), BmapOperator.Get.getCode(), probe.packet()[2] & 0xff);
        }
    }

    @Test
    public void containsTheStageTwoAllowlistInDeterministicOrder() {
        List<String> stageTwoNames = SnapshotProbes.BASE.stream()
                .map(ReadProbe::name)
                .filter(name -> name.equals("settings.eq")
                        || name.equals("settings.shortcut")
                        || name.equals("settings.multipoint")
                        || name.equals("device_management.info")
                        || name.equals("device_management.list")
                        || name.equals("device_management.routing")
                        || name.equals("audio_modes.default")
                        || name.equals("audio_modes.persistence"))
                .toList();

        assertEquals(List.of(
                "settings.eq",
                "settings.shortcut",
                "settings.multipoint",
                "device_management.info",
                "device_management.list",
                "device_management.routing",
                "audio_modes.default",
                "audio_modes.persistence"
        ), stageTwoNames);
    }

    @Test
    public void allowlistContainsOnlyApprovedAddresses() {
        Set<BmapAddress> approved = Set.of(
                new BmapAddress(0, 1),
                new BmapAddress(0, 5),
                new BmapAddress(0, 15),
                new BmapAddress(1, 0),
                new BmapAddress(1, 2),
                new BmapAddress(1, 3),
                new BmapAddress(1, 5),
                new BmapAddress(1, 6),
                new BmapAddress(1, 7),
                new BmapAddress(1, 9),
                new BmapAddress(1, 10),
                new BmapAddress(2, 2),
                new BmapAddress(4, 0),
                new BmapAddress(4, 4),
                new BmapAddress(4, 5),
                new BmapAddress(4, 6),
                new BmapAddress(4, 12),
                new BmapAddress(31, 0),
                new BmapAddress(31, 2),
                new BmapAddress(31, 3),
                new BmapAddress(31, 4),
                new BmapAddress(31, 5),
                new BmapAddress(31, 6),
                new BmapAddress(31, 7),
                new BmapAddress(31, 8),
                new BmapAddress(31, 10),
                new BmapAddress(31, 11)
        );

        assertTrue(approved.containsAll(SnapshotProbes.BASE.stream()
                .map(ReadProbe::address)
                .collect(Collectors.toSet())));
    }

    @Test
    public void deviceDetailReadsAreDerivedFromTheListIdentifiers() {
        byte[] payload = hex(
                "03"
                        + "111111111111"
                        + "222222222222"
                        + "333333333333"
                        + "444444444444");

        List<ReadProbe> probes = SnapshotProbes.deviceDetails(payload);

        assertEquals(8, probes.size());
        assertEquals("device_management.info.0", probes.get(0).name());
        assertEquals("device_management.extended_info.0", probes.get(1).name());
        assertArrayEquals(hex("04050106111111111111"), probes.get(0).packet());
        assertArrayEquals(hex("04060106444444444444"), probes.get(7).packet());
        for (ReadProbe probe : probes) {
            assertEquals(BmapOperator.Get.getCode(), probe.packet()[2] & 0xff);
        }
    }

    @Test
    public void malformedDeviceListDoesNotCreateSpeculativeReads() {
        assertTrue(SnapshotProbes.deviceDetails(hex("0311111111")).isEmpty());
    }

    @Test
    public void readProbeCorrelatesOnlyStatusOrErrorAtItsAddress() {
        ReadProbe probe = new ReadProbe("settings.eq", new BmapAddress(1, 7));
        BmapFrame status = frame(1, 7, BmapOperator.Status);
        BmapFrame error = frame(1, 7, BmapOperator.Error);
        BmapFrame unrelated = frame(1, 9, BmapOperator.Status);
        BmapFrame invalidOperator = frame(1, 7, BmapOperator.Processing);

        assertTrue(probe.accepts(status));
        assertTrue(probe.accepts(error));
        assertFalse(probe.accepts(unrelated));
        assertFalse(probe.accepts(invalidOperator));
        assertArrayEquals(new byte[]{1, 7, 1, 0}, probe.packet());
    }

    @Test
    public void runnerDoesNotCorrelateAnUnrelatedFrame() throws Exception {
        ReadProbe probe = new ReadProbe("settings.eq", new BmapAddress(1, 7));
        byte[] responses = hex("01090301000107030cf60a0200f60a0401f60a0002");
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result result = runner.exchange(
                new ByteArrayInputStream(responses),
                new ByteArrayOutputStream(),
                probe,
                100);

        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, result.outcome());
        assertEquals(1, result.unrelatedFrames().size());
        assertEquals(new BmapAddress(1, 9), result.unrelatedFrames().get(0).getAddress());
        assertEquals(new BmapAddress(1, 7), result.response().getAddress());
    }

    @Test
    public void runnerDecodesAResponseSplitAcrossReads() throws Exception {
        ReadProbe probe = new ReadProbe("settings.eq", new BmapAddress(1, 7));
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result result = runner.exchange(
                new SplitInputStream(
                        hex("0107030cf60a"),
                        hex("0200f60a0401f60a0002")),
                new ByteArrayOutputStream(),
                probe,
                100);

        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, result.outcome());
        assertArrayEquals(
                hex("f60a0200f60a0401f60a0002"),
                result.response().getPayload());
    }

    @Test
    public void unsupportedResponseDoesNotPreventTheNextProbe() throws Exception {
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result unsupported = runner.exchange(
                new ByteArrayInputStream(hex("040c040104")),
                new ByteArrayOutputStream(),
                new ReadProbe("device_management.routing", new BmapAddress(4, 12)),
                100);
        ReadProbeRunner.Result supported = runner.exchange(
                new ByteArrayInputStream(hex("1f04030100")),
                new ByteArrayOutputStream(),
                new ReadProbe("audio_modes.default", new BmapAddress(31, 4)),
                100);

        assertEquals(ReadProbeRunner.Outcome.UNSUPPORTED, unsupported.outcome());
        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, supported.outcome());
    }

    @Test
    public void timeoutDoesNotPreventTheNextProbe() throws Exception {
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result timedOut = runner.exchange(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                new ReadProbe("device_management.routing", new BmapAddress(4, 12)),
                1);
        ReadProbeRunner.Result supported = runner.exchange(
                new ByteArrayInputStream(hex("1f04030100")),
                new ByteArrayOutputStream(),
                new ReadProbe("audio_modes.default", new BmapAddress(31, 4)),
                100);

        assertEquals(ReadProbeRunner.Outcome.TIMED_OUT, timedOut.outcome());
        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, supported.outcome());
    }

    @Test
    public void malformedResponseDoesNotPreventTheNextProbe() throws Exception {
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result malformed = runner.exchange(
                new ByteArrayInputStream(hex("0107030cf60a")),
                new ByteArrayOutputStream(),
                new ReadProbe("settings.eq", new BmapAddress(1, 7)),
                1);
        ReadProbeRunner.Result supported = runner.exchange(
                new ByteArrayInputStream(hex("010a030107")),
                new ByteArrayOutputStream(),
                new ReadProbe("settings.multipoint", new BmapAddress(1, 10)),
                100);

        assertEquals(ReadProbeRunner.Outcome.MALFORMED, malformed.outcome());
        assertEquals(16, malformed.malformed().getExpectedFrameLength());
        assertEquals(6, malformed.malformed().getActualByteCount());
        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, supported.outcome());
    }

    @Test
    public void deviceInfoCanCompleteWithResultAfterProcessing() throws Exception {
        ReadProbe probe = new ReadProbe(
                "device_management.info.0",
                new BmapAddress(4, 5),
                hex("111111111111"),
                true);
        ReadProbeRunner runner = new ReadProbeRunner(new NoOpListener());

        ReadProbeRunner.Result result = runner.exchange(
                new ByteArrayInputStream(hex(
                        "04050700"
                                + "0405060a11111111111101000051")),
                new ByteArrayOutputStream(),
                probe,
                100);

        assertEquals(ReadProbeRunner.Outcome.SUPPORTED, result.outcome());
        assertEquals(BmapOperator.Result, result.response().getOperator());
        assertTrue(result.unrelatedFrames().isEmpty());
    }

    private static BmapFrame frame(int block, int function, BmapOperator operator) {
        return new BmapFrame(
                new BmapAddress(block, function),
                operator,
                operator.getCode(),
                new byte[0]);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(
                    value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static final class NoOpListener implements ReadProbeRunner.Listener {
        @Override
        public void onReceiveChunk(byte[] bytes) {
        }

        @Override
        public void onFrame(BmapFrame frame, boolean matchesCurrentProbe) {
        }
    }

    private static final class SplitInputStream extends ByteArrayInputStream {
        private final int firstChunkSize;
        private boolean firstChunk = true;

        private SplitInputStream(byte[] firstChunk, byte[] secondChunk) {
            super(concat(firstChunk, secondChunk));
            firstChunkSize = firstChunk.length;
        }

        @Override
        public synchronized int available() {
            if (firstChunk) {
                return Math.min(firstChunkSize, super.available());
            }
            return super.available();
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length);
            firstChunk = false;
            return count;
        }

        private static byte[] concat(byte[] first, byte[] second) {
            byte[] result = new byte[first.length + second.length];
            System.arraycopy(first, 0, result, 0, first.length);
            System.arraycopy(second, 0, result, first.length, second.length);
            return result;
        }
    }
}
