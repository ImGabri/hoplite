package slotcycler

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.command.permission.Permission
import net.minecraft.command.permission.PermissionLevel
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Sent by the client at the end of a ghost run: "make the copy real".
 * Slots are hotbar indexes (0-8).
 */
data class MakeRealPayload(val slotA: Int, val slotB: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<MakeRealPayload> = ID

    companion object {
        val ID: CustomPayload.Id<MakeRealPayload> =
            CustomPayload.Id(Identifier.of("slotcycler", "make_real"))
        val CODEC: PacketCodec<RegistryByteBuf, MakeRealPayload> = PacketCodec.tuple(
            PacketCodecs.VAR_INT, MakeRealPayload::slotA,
            PacketCodecs.VAR_INT, MakeRealPayload::slotB,
            ::MakeRealPayload
        )
    }
}

/**
 * The server-side part. It runs on dedicated servers and on the integrated
 * server of a single-player world, and must not touch any client class.
 *
 * The server owns the inventory, so this is the only place a copy can be made
 * that really exists. Because that creates an item, only the world's host and
 * operators may ask for it.
 */
class SlotCyclerServer : ModInitializer {

    override fun onInitialize() {
        PayloadTypeRegistry.playC2S().register(MakeRealPayload.ID, MakeRealPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(MakeRealPayload.ID) { payload, context ->
            makeReal(context.server(), context.player(), payload)
        }
    }

    private fun makeReal(server: MinecraftServer, player: ServerPlayerEntity, payload: MakeRealPayload) {
        val a = payload.slotA
        val b = payload.slotB
        if (a in 0..8 && b in 0..8 && a != b) {
            if (mayCopy(server, player)) {
                // Whichever of the two slots is empty gets one copy of the other's item.
                val inventory = player.inventory
                val (from, to) = if (inventory.getStack(a).isEmpty) b to a else a to b
                val source = inventory.getStack(from)
                if (!source.isEmpty && inventory.getStack(to).isEmpty) {
                    inventory.setStack(to, source.copyWithCount(1))
                }
            } else {
                player.sendMessage(
                    Text.literal("[SlotCycler] Only operators can make the copy real on this server"),
                    false
                )
            }
        }
        // Always resend the real inventory so the client drops its ghost clicks.
        player.playerScreenHandler.syncState()
    }

    private fun mayCopy(server: MinecraftServer, player: ServerPlayerEntity): Boolean =
        server.isHost(player.playerConfigEntry) ||
            player.permissions.hasPermission(Permission.Level(PermissionLevel.GAMEMASTERS))
}
