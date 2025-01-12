package org.itxtech.nemisys.network.protocol.mcpe;

import org.itxtech.nemisys.utils.Skin;

import java.util.UUID;

/**
 * @author Nukkit Project Team
 */
public class PlayerListPacket extends DataPacket {

    public static final byte NETWORK_ID = ProtocolInfo.PLAYER_LIST_PACKET;

    public static final byte TYPE_ADD = 0;
    public static final byte TYPE_REMOVE = 1;

    public byte type;
    public Entry[] entries = new Entry[0];

    @Override
    public void decode() {
        type = (byte) getByte();

        int len = (int) getUnsignedVarInt();
        entries = new Entry[len];

        while (len-- > 0) {
            Entry entry = new Entry(getUUID());

            if (type == TYPE_ADD) {
                entry.entityId = getEntityUniqueId();
                entry.name = getString();

                entry.xboxUserId = getString();
                entry.platformChatId = this.getString();
                entry.buildPlatform = getLInt();
                entry.skin = getSkin();
                entry.isTeacher = getBoolean();
                entry.isHost = getBoolean();
            }

            entries[len] = entry;
        }

        if (type == TYPE_ADD) {
            for (Entry entry : this.entries) {
                entry.skin.setTrusted(this.getBoolean());
            }
        }
    }

    @Override
    public void encode() {
        this.reset();
        this.putByte(this.type);
        this.putUnsignedVarInt(this.entries.length);
        for (Entry entry : this.entries) {
            this.putUUID(entry.uuid);

            if (type == TYPE_ADD) {
                this.putVarLong(entry.entityId);
                this.putString(entry.name);
                this.putString(entry.xboxUserId);
                this.putString(entry.platformChatId);
                this.putLInt(entry.buildPlatform);
                this.putSkin(entry.skin);
                this.putBoolean(entry.isTeacher);
                this.putBoolean(entry.isHost);
            }
        }

        if (type == TYPE_ADD) {
            for (Entry entry : this.entries) {
                this.putBoolean(entry.skin.isTrusted());
            }
        }
    }

    @Override
    public byte pid() {
        return NETWORK_ID;
    }

    public static class Entry {

        public final UUID uuid;
        public long entityId = 0;
        public String name = "";
        public String xboxUserId = "";
        public String platformChatId = "";
        public int buildPlatform = -1;
        public Skin skin;
        public boolean isTeacher;
        public boolean isHost;

        public Entry(UUID uuid) {
            this.uuid = uuid;
        }

        public Entry(UUID uuid, long entityId, String name, Skin skin) {
            this(uuid, entityId, name, skin, "");
        }

        public Entry(UUID uuid, long entityId, String name, Skin skin, String xboxUserId) {
            this.uuid = uuid;
            this.entityId = entityId;
            this.name = name;
            this.skin = skin;
            this.xboxUserId = xboxUserId == null ? "" : xboxUserId;
        }
    }

}
