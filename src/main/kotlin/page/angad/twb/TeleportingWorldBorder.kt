package page.angad.twb

import com.mojang.brigadier.context.CommandContext
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.ChatFormatting.BOLD
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import kotlin.Int
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val EXPAND_TIME = 12.hours
const val BORDER_BASE = 1024.0
const val BORDER_EXPAND = 6e7

class ExpansionData private constructor(wheat: Int, expansions: Int, store: Int, last: Instant) : SavedData() {
	var expansions: Int = expansions
		private set
	var store: Int = store
		private set
	var last: Instant = last
		private set

	var wheat: Int = wheat
		private set

	val cost: Int
		get() = (32.0 * 1.35.pow(expansions)).let(::ceil).toInt()
	val needed: Int
		get() = cost - wheat
	val expanded: Boolean
		get() = Clock.System.now().minus(last) < EXPAND_TIME
	val border: Double
		get() = if (expanded) BORDER_EXPAND else BORDER_BASE

	fun increment(by: Int, server: MinecraftServer) {
		wheat += by

		var adds = 0

		while (true) {
			if (wheat < cost) break

			wheat -= cost
			expansions++
			store++
			adds++
		}

		/*
		 * Added **5** new wheat with **0** new expansions
		 *     **27** wheat until next expansion
		 *     **0** expansions available
		 */
		server.playerList.broadcastSystemMessage(
			Component.empty()
				.append("Added ").append(Component.literal("$by").withStyle(BOLD)).append(" wheat with ").append(Component.literal("$adds").withStyle(BOLD)).append(" new expansions")
				.append("\n    ").append(Component.literal("$needed").withStyle(BOLD)).append(" wheat until next expansion")
				.append("\n    ").append(Component.literal("$store").withStyle(BOLD).append(" expansions available")),
			false,
		)


		setDirty()
	}

	fun update(server: MinecraftServer) {
		server.allLevels.forEach { if (it.worldBorder.size != border) it.worldBorder.size = border }
	}

	fun expand(context: CommandContext<CommandSourceStack>): Int {
		if (store == 0) {
			context.source.sendFailure(Component.literal("No expansions available, need $needed more wheat"))
			return 0
		}

		if (expanded) {
			context.source.sendFailure(Component.literal("The border is currently expanded. Go explore!"))
			return 0
		}

		store--
		last = Clock.System.now()

		context.source.sendSuccess(
			{ Component.literal("Border expanded for $EXPAND_TIME. $store expansions left") },
			true
		)

		setDirty()

		return 1
	}

	fun check(context: CommandContext<CommandSourceStack>): Int {
		context.source.sendSuccess(
			{
				Component.empty()
					.append(Component.literal("$needed").withStyle(BOLD)).append(" wheat until next expansion")
					.append(Component.literal("\n$store").withStyle(BOLD).append(" expansions available"))
			},
			true
		)

		return 1
	}

	companion object {
		private fun empty(): ExpansionData = ExpansionData(0, 0, 0, Instant.DISTANT_PAST)

		private val CODEC_INSTANT: Codec<Instant> = Codec.LONG.xmap(
			{ Instant.fromEpochSeconds(it) },
			{ it.epochSeconds }
		)

		private val CODEC: Codec<ExpansionData> = RecordCodecBuilder.create { instance ->
			instance.group(
				Codec.INT.fieldOf("wheat").forGetter { it.wheat },
				Codec.INT.fieldOf("expansions").forGetter { it.expansions },
				Codec.INT.fieldOf("store").forGetter { it.store },
				CODEC_INSTANT.fieldOf("last").forGetter { it.last },
			).apply(instance, ::ExpansionData)
		}

		private val TYPE: SavedDataType<ExpansionData> = SavedDataType(
			Identifier.fromNamespaceAndPath(TeleportingWorldBorder.MOD_ID, "expansions"),
			::empty,
			CODEC,
			DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES,
		)

		fun retrieve(server: MinecraftServer): ExpansionData {
			val level = server.getLevel(ServerLevel.OVERWORLD) ?: return empty()
			return level.dataStorage.computeIfAbsent(TYPE)
		}
	}
}

object TeleportingWorldBorder : ModInitializer {
	const val MOD_ID = "twb"

	private val teleported: MutableSet<UUID> = mutableSetOf()
	private lateinit var expansion: ExpansionData

	fun delta(border: WorldBorder, ent: Entity): Pair<Double, Double> {
		val dx = when {
			ent.x < 0 -> ent.x - border.minX
			ent.x > 0 -> border.maxX - ent.x
			else -> border.size / 2
		}
		val dz = when {
			ent.z < 0 -> ent.z - border.minZ
			ent.z > 0 -> border.maxZ - ent.z
			else -> border.size / 2
		}

		return dx to dz
	}

	override fun onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			expansion = ExpansionData.retrieve(server)
			server.allLevels.forEach { it.worldBorder.damagePerBlock = 0.0 }
		}

		ServerTickEvents.END_SERVER_TICK.register { server: MinecraftServer ->
			server.playerList.players.forEach { player ->
				val border = player.level().worldBorder
				val (dx, dz) = delta(border, player)

				if (dx < 0 || dz < 0) {
					val level = player.level()
					val topY = level.getChunk(0, 0).getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0).toDouble()
					player.teleportTo(0.0, topY + 10.0, 0.0)
					teleported.add(player.uuid)
				}
			}

			server.allLevels.forEach { level ->
				val border = level.worldBorder

				level.allEntities.forEach { ent ->
					if (ent !is ItemEntity) return@forEach
					if (!ent.item.`is`(Items.WHEAT)) return@forEach

					val (dx, dz) = delta(border, ent)
					val ct = ent.item.count

					if (dx < 0.5 || dz < 0.5) {
						ent.discard()
						expansion.increment(ct, server)
					}
				}
			}

			expansion.update(server)
		}

		ServerLivingEntityEvents.ALLOW_DAMAGE.register { ent, damage, _ ->
			!(ent is ServerPlayer && damage.`is`(DamageTypes.FALL) && teleported.remove(ent.uuid))
		}

		CommandRegistrationCallback.EVENT.register { dsp, _, _ ->
			dsp.register(
				Commands.literal("twb")
					.then(Commands.literal("check").executes { expansion.check(it) })
					.then(Commands.literal("expand").executes { expansion.expand(it) })
			)
		}
	}
}