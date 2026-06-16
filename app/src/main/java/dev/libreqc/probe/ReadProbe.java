package dev.libreqc.probe;

import dev.libreqc.bmap.BmapAddress;
import dev.libreqc.bmap.BmapFrame;
import dev.libreqc.bmap.BmapOperator;
import dev.libreqc.bmap.BmapPackets;

import java.util.Arrays;
import java.util.Objects;

public final class ReadProbe {
    private final String name;
    private final BmapAddress address;
    private final BmapOperator operator;
    private final byte[] payload;
    private final boolean acceptsResult;

    public ReadProbe(String name, BmapAddress address) {
        this(name, address, BmapOperator.Get, new byte[0], false);
    }

    public ReadProbe(String name, BmapAddress address, byte[] payload) {
        this(name, address, BmapOperator.Get, payload, false);
    }

    public ReadProbe(String name, BmapAddress address, BmapOperator operator, byte[] payload) {
        this(name, address, operator, payload, false);
    }

    public ReadProbe(
            String name, BmapAddress address, byte[] payload, boolean acceptsResult) {
        this(name, address, BmapOperator.Get, payload, acceptsResult);
    }

    public ReadProbe(
            String name, BmapAddress address, BmapOperator operator, byte[] payload, boolean acceptsResult) {
        this.name = Objects.requireNonNull(name);
        this.address = Objects.requireNonNull(address);
        this.operator = Objects.requireNonNull(operator);
        this.payload = Arrays.copyOf(payload, payload.length);
        this.acceptsResult = acceptsResult;
    }

    public String name() {
        return name;
    }

    public BmapAddress address() {
        return address;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public byte[] packet() {
        return BmapPackets.encode(address, operator, payload);
    }

    public boolean accepts(BmapFrame frame) {
        return address.equals(frame.getAddress())
                && (frame.getOperator() == BmapOperator.Status
                || frame.getOperator() == BmapOperator.Error
                || (acceptsResult && frame.getOperator() == BmapOperator.Result));
    }

    public boolean isRelated(BmapFrame frame) {
        return address.equals(frame.getAddress())
                && (accepts(frame) || frame.getOperator() == BmapOperator.Processing);
    }
}
