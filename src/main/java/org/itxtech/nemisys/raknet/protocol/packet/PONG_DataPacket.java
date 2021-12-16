package org.itxtech.nemisys.raknet.protocol.packet;

import org.itxtech.nemisys.raknet.protocol.Packet;

/**
 * author: MagicDroidX
 * Nukkit Project
 */
public class PONG_DataPacket extends Packet {
    public static byte ID = (byte) 0x03;

    @Override
    public byte getID() {
        return ID;
    }

    public long pingID;
    public long pongID;

    @Override
    public void encode() {
        super.encode();
        this.putLong(this.pingID);
        this.putLong(this.pongID);
    }

    @Override
    public void decode() {
        super.decode();
        this.pingID = this.getLong();
        this.pongID = this.getLong();
    }

    public static final class Factory implements Packet.PacketFactory {

        @Override
        public Packet create() {
            return new PONG_DataPacket();
        }

    }
}
