/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.java.world;

import com.github.steveice10.mc.protocol.data.game.chunk.Column;
import com.github.steveice10.mc.protocol.data.game.world.block.BlockState;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.Position;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerChunkDataPacket;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.nbt.stream.NBTOutputStream;
import com.nukkitx.nbt.tag.CompoundTag;
import com.nukkitx.network.VarInts;
import com.nukkitx.protocol.bedrock.packet.LevelChunkPacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.BiomeTranslator;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;
import org.geysermc.connector.network.translators.world.chunk.ChunkPosition;
import org.geysermc.connector.utils.ChunkUtils;
import org.geysermc.connector.network.translators.world.chunk.ChunkSection;

@Translator(packet = ServerChunkDataPacket.class)
public class JavaChunkDataTranslator extends PacketTranslator<ServerChunkDataPacket> {

    // Used for determining if we should process non-full chunks
    private static final boolean CACHE_CHUNKS;

    static {
        CACHE_CHUNKS = GeyserConnector.getInstance().getConfig().isCacheChunks();
    }

    @Override
    public void translate(ServerChunkDataPacket packet, GeyserSession session) {
        if (session.isSpawned()) {
            ChunkUtils.updateChunkPosition(session, session.getPlayerEntity().getPosition().toInt());
        }

        if (packet.getColumn().getBiomeData() == null && !CACHE_CHUNKS) {
            // Non-full chunk without chunk caching
            session.getConnector().getLogger().debug("Not sending non-full chunk because chunk caching is off.");
            return;
        }

        // Non-full chunks don't have all the chunk data, and Bedrock won't accept that
        final boolean isNonFullChunk = (packet.getColumn().getBiomeData() == null);

        if (isNonFullChunk && !session.getChunkCache().getLoadedChunks().contains(new ChunkPosition(packet.getColumn().getX(), packet.getColumn().getZ()))) {
            // Don't send partial chunk updates before the full chunk is loaded
            session.getChunkCache().getCachedNonFullChunks().add(packet.getColumn());
            return;
        }

        sendChunk(session, packet.getColumn(), isNonFullChunk, packet.getColumn().getBiomeData());
    }

    private void sendChunk(GeyserSession session, Column column, boolean isNonFullChunk, int[] biomeData) {
       GeyserConnector.getInstance().getGeneralThreadPool().execute(() -> {
            try {
                byte[] bedrockBiome;
                if (biomeData == null) {
                    if (session.getChunkCache().getLoadedChunks().contains(new ChunkPosition(column.getX(), column.getZ()))) {
                        bedrockBiome = BiomeTranslator.toBedrockBiome(session.getConnector().getWorldManager().getBiomeDataAt(session, column.getX(), column.getZ()));
                    } else {
                        session.getChunkCache().getCachedNonFullChunks().add(column);
                        return;
                    }
                } else {
                    bedrockBiome = BiomeTranslator.toBedrockBiome(column.getBiomeData());
                }

                ChunkUtils.ChunkData chunkData = ChunkUtils.translateToBedrock(session, column, isNonFullChunk);
                ByteBuf byteBuf = Unpooled.buffer(32);
                ChunkSection[] sections = chunkData.sections;

                int sectionCount = sections.length - 1;
                while (sectionCount >= 0 && sections[sectionCount].isEmpty()) {
                    sectionCount--;
                }
                sectionCount++;

                for (int i = 0; i < sectionCount; i++) {
                    ChunkSection section = chunkData.sections[i];
                    section.writeToNetwork(byteBuf);
                }

                byteBuf.writeBytes(bedrockBiome); // Biomes - 256 bytes
                byteBuf.writeByte(0); // Border blocks - Edu edition only
                VarInts.writeUnsignedInt(byteBuf, 0); // extra data length, 0 for now

                ByteBufOutputStream stream = new ByteBufOutputStream(Unpooled.buffer());
                NBTOutputStream nbtStream = NbtUtils.createNetworkWriter(stream);
                for (CompoundTag blockEntity : chunkData.getBlockEntities()) {
                    nbtStream.write(blockEntity);
                }

                byteBuf.writeBytes(stream.buffer());

                byte[] payload = new byte[byteBuf.writerIndex()];
                byteBuf.readBytes(payload);

                LevelChunkPacket levelChunkPacket = new LevelChunkPacket();
                levelChunkPacket.setSubChunksLength(sectionCount);
                levelChunkPacket.setCachingEnabled(false);
                levelChunkPacket.setChunkX(column.getX());
                levelChunkPacket.setChunkZ(column.getZ());
                levelChunkPacket.setData(payload);
                session.sendUpstreamPacket(levelChunkPacket);

                // Some block entities need to be loaded in later or else text doesn't show (signs) or they crash the game (end gateway blocks)
                for (Object2IntMap.Entry<CompoundTag> blockEntityEntry : chunkData.getLoadBlockEntitiesLater().object2IntEntrySet()) {
                    int x = blockEntityEntry.getKey().getInt("x");
                    int y = blockEntityEntry.getKey().getInt("y");
                    int z = blockEntityEntry.getKey().getInt("z");
                    ChunkUtils.updateBlock(session, new BlockState(blockEntityEntry.getIntValue()), new Position(x, y, z));
                }
                chunkData.getLoadBlockEntitiesLater().clear();
                session.getChunkCache().addToCache(column);
                if (!isNonFullChunk) {
                    for (Column nonFullColumn : session.getChunkCache().getCachedNonFullChunks()) {
                        if (nonFullColumn.getX() == column.getX() && nonFullColumn.getZ() == column.getZ()) {
                            sendChunk(session, nonFullColumn, true, column.getBiomeData());
                            session.getChunkCache().getCachedNonFullChunks().remove(nonFullColumn);
                            System.out.println(session.getChunkCache().getCachedNonFullChunks().size());
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
