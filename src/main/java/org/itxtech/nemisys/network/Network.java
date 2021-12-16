package org.itxtech.nemisys.network;

import org.itxtech.nemisys.Nemisys;
import org.itxtech.nemisys.Player;
import org.itxtech.nemisys.Server;
import org.itxtech.nemisys.nbt.stream.FastByteArrayOutputStream;
import org.itxtech.nemisys.network.protocol.mcpe.*;
import org.itxtech.nemisys.utils.Binary;
import org.itxtech.nemisys.utils.BinaryStream;
import org.itxtech.nemisys.utils.ThreadCache;
import org.itxtech.nemisys.utils.VarInt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * author: MagicDroidX
 * Nukkit Project
 */
public class Network {

    private static final ThreadLocal<Inflater> INFLATER_RAW = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<Deflater> DEFLATER_RAW = ThreadLocal.withInitial(() -> new Deflater(7, true));
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[2 * 1024 * 1024]);

    private Class<? extends DataPacket>[] packetPool = new Class[256];
    private Class<? extends DataPacket>[] handleablePacketPool = new Class[256]; // Nemisys handleable (client to proxy)

    private Server server;

    private Set<SourceInterface> interfaces = new HashSet<>();

    private Set<AdvancedSourceInterface> advancedInterfaces = new HashSet<>();

    private double upload = 0;
    private double download = 0;

    private String name;

    public Network(Server server) {
        this.registerPackets();
        this.server = server;
    }

    public static byte[] inflateRaw(byte[] data) throws IOException, DataFormatException {
        Inflater inflater = INFLATER_RAW.get();
        inflater.reset();
        inflater.setInput(data);
        inflater.finished();

        FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
        bos.reset();
        byte[] buf = BUFFER.get();
        while (!inflater.finished()) {
            int i = inflater.inflate(buf);
            bos.write(buf, 0, i);
        }
        return bos.toByteArray();
    }

    public static byte[] deflateRaw(byte[] data, int level) throws IOException {
        Deflater deflater = DEFLATER_RAW.get();
        deflater.reset();
        deflater.setLevel(level);
        deflater.setInput(data);
        deflater.finish();
        FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
        bos.reset();
        byte[] buffer = BUFFER.get();
        while (!deflater.finished()) {
            int i = deflater.deflate(buffer);
            bos.write(buffer, 0, i);
        }

        return bos.toByteArray();
    }

    public static byte[] deflateRaw(byte[][] datas, int level) throws IOException {
        Deflater deflater = DEFLATER_RAW.get();
        deflater.reset();
        deflater.setLevel(level);
        FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
        bos.reset();
        byte[] buffer = BUFFER.get();

        for (byte[] data : datas) {
            deflater.setInput(data);
            while (!deflater.needsInput()) {
                int i = deflater.deflate(buffer);
                bos.write(buffer, 0, i);
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            int i = deflater.deflate(buffer);
            bos.write(buffer, 0, i);
        }
        //Deflater::end is called the time when the process exits.
        return bos.toByteArray();
    }

    public void addStatistics(double upload, double download) {
        this.upload += upload;
        this.download += download;
    }

    public double getUpload() {
        return upload;
    }

    public double getDownload() {
        return download;
    }

    public void resetStatistics() {
        this.upload = 0;
        this.download = 0;
    }

    public Set<SourceInterface> getInterfaces() {
        return interfaces;
    }

    public void processInterfaces() {
        for (SourceInterface interfaz : this.interfaces) {
            try {
                interfaz.process();
            } catch (Exception e) {
                if (Nemisys.DEBUG > 1) {
                    this.server.getLogger().logException(e);
                }

                interfaz.emergencyShutdown();
                this.unregisterInterface(interfaz);
                this.server.getLogger().critical(this.server.getLanguage().translateString("nemisys.server.networkError", new String[]{interfaz.getClass().getName(), e.getMessage()}), e);
            }
        }
    }

    public void registerInterface(SourceInterface interfaz) {
        this.interfaces.add(interfaz);
        if (interfaz instanceof AdvancedSourceInterface) {
            this.advancedInterfaces.add((AdvancedSourceInterface) interfaz);
            ((AdvancedSourceInterface) interfaz).setNetwork(this);
        }
        interfaz.setName(this.name);
    }

    public void unregisterInterface(SourceInterface interfaz) {
        this.interfaces.remove(interfaz);
        this.advancedInterfaces.remove(interfaz);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.updateName();
    }

    public void updateName() {
        for (SourceInterface interfaz : this.interfaces) {
            interfaz.setName(this.name);
        }
    }

    public void registerPacket(byte id, Class<? extends DataPacket> clazz) {
        this.registerPacket(id, clazz, false);
    }

    public void registerPacket(byte id, Class<? extends DataPacket> clazz, boolean handleable) {
        this.packetPool[id & 0xff] = clazz;
        if (handleable) {
            this.handleablePacketPool[id & 0xff] = clazz;
        }
    }

    public Server getServer() {
        return server;
    }

    public void processBatch(BatchPacket packet, Player player) {
        byte[] data;
        try {
            data = Network.inflateRaw(packet.payload);
            //data = Zlib.inflate(packet.payload, 2 * 1024 * 1024);
        } catch (Exception e) {
            return;
        }

        int len = data.length;
        BinaryStream stream = new BinaryStream(data);
        try {
            List<DataPacket> packets = new ArrayList<>();
            int count = 0;
            while (stream.offset < len) {
                count++;
                if (count >= 1000) {
                    player.close("Illegal Batch Packet");
                    return;
                }
                byte[] buf = stream.getByteArray();

                ByteArrayInputStream bais = new ByteArrayInputStream(buf);
                int header = (int) VarInt.readUnsignedVarInt(bais);

                // | Client ID | Sender ID | Packet ID |
                // |   2 bits  |   2 bits  |  10 bits  |
                int packetId = header & 0x3ff;

                DataPacket pk = this.getHandleablePacket(packetId);

                if (pk != null) {
                    pk.setBuffer(buf, buf.length - bais.available());

                    pk.decode();

                    packets.add(pk);
                }
            }

            processPackets(player, packets);

        } catch (Exception e) {
            if (Nemisys.DEBUG > 0) {
                this.server.getLogger().debug("BatchPacket 0x" + Binary.bytesToHexString(packet.payload));
                this.server.getLogger().logException(e);
            }
        }
    }

    /**
     * Process packets obtained from batch packets
     * Required to perform additional analyses and filter unnecessary packets
     *
     * @param packets
     */
    public void processPackets(Player player, List<DataPacket> packets) {
        if (packets.isEmpty()) return;
        packets.forEach(player::addOutgoingPacket);
    }


    public DataPacket getPacket(byte id) {
        Class<? extends DataPacket> clazz = this.packetPool[id & 0xff];
        if (clazz != null) {
            try {
                return clazz.newInstance();
            } catch (Exception e) {
                Server.getInstance().getLogger().logException(e);
            }
        }
        return new GenericPacket();
    }

    public DataPacket getHandleablePacket(int id) {
        Class<? extends DataPacket> clazz = this.handleablePacketPool[id];
        if (clazz != null) {
            try {
                return clazz.newInstance();
            } catch (Exception e) {
                Server.getInstance().getLogger().logException(e);
            }
        }
        return new GenericPacket();
    }

    public void sendPacket(String address, int port, byte[] payload) {
        for (AdvancedSourceInterface interfaz : this.advancedInterfaces) {
            interfaz.sendRawPacket(address, port, payload);
        }
    }

    public void blockAddress(String address) {
        this.blockAddress(address, 300);
    }

    public void blockAddress(String address, int timeout) {
        for (AdvancedSourceInterface interfaz : this.advancedInterfaces) {
            interfaz.blockAddress(address, timeout);
        }
    }

    private void registerPackets() {
        this.packetPool = new Class[256];
        this.handleablePacketPool = new Class[256];

        this.registerPacket(ProtocolInfo.LOGIN_PACKET, LoginPacket.class, true);
        this.registerPacket(ProtocolInfo.DISCONNECT_PACKET, DisconnectPacket.class);
        this.registerPacket(ProtocolInfo.BATCH_PACKET, BatchPacket.class, true);
        this.registerPacket(ProtocolInfo.PLAYER_LIST_PACKET, PlayerListPacket.class);
        this.registerPacket(ProtocolInfo.AVAILABLE_COMMANDS_PACKET, AvailableCommandsPacket.class);
        this.registerPacket(ProtocolInfo.ADD_ENTITY_PACKET, AddEntityPacket.class);
        this.registerPacket(ProtocolInfo.ADD_PLAYER_PACKET, AddPlayerPacket.class);
        this.registerPacket(ProtocolInfo.ADD_ITEM_ENTITY_PACKET, AddItemEntityPacket.class);
        this.registerPacket(ProtocolInfo.ADD_PAINTING_PACKET, AddPaintingPacket.class);
        this.registerPacket(ProtocolInfo.REMOVE_ENTITY_PACKET, RemoveEntityPacket.class);
        this.registerPacket(ProtocolInfo.TEXT_PACKET, TextPacket.class, true);
        this.registerPacket(ProtocolInfo.COMMAND_REQUEST_PACKET, CommandRequestPacket.class, true);
        this.registerPacket(ProtocolInfo.REMOVE_OBJECTIVE_PACKET, RemoveObjectivePacket.class);
        this.registerPacket(ProtocolInfo.SET_DISPLAY_OBJECTIVE_PACKET, SetDisplayObjectivePacket.class);
        this.registerPacket(ProtocolInfo.SET_SCORE_PACKET, SetScorePacket.class);
    }
}
