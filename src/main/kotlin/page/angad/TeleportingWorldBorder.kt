package page.angad

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

object TeleportingWorldBorder : ModInitializer {
	private val teleported: MutableSet<UUID> = mutableSetOf()

	override fun onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			server.overworld().worldBorder.damagePerBlock = 0.0
		}

		ServerTickEvents.END_SERVER_TICK.register { server: MinecraftServer ->
			server.playerList.players.forEach { player ->
				val border = player.level().worldBorder
				val x = player.x
				val z = player.z

				val dx = when {
					x < border.minX -> border.minX - x
					x > border.maxX -> border.maxX - x
					else -> 0.0
				}
				val dz = when {
					z < border.minZ -> border.minZ - z
					z > border.maxZ -> border.maxZ - z
					else -> 0.0
				}

				if (dx != 0.0 || dz != 0.0) {
					val level = player.level()
					val topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0).toDouble()
					player.teleportTo(0.0, topY + 10.0, 0.0)
					teleported.add(player.uuid)
				}
			}
		}

		ServerLivingEntityEvents.ALLOW_DAMAGE.register { ent, damage, _ ->
			!(ent is ServerPlayer && damage.`is`(DamageTypes.FALL) && teleported.remove(ent.uuid))
		}
	}
}