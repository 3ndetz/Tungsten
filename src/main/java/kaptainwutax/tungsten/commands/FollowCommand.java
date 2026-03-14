package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.task.FollowEntityTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

public class FollowCommand extends Command {

    public FollowCommand(TungstenMod mod) throws CommandException {
        super("follow", "Follow a player or stop following", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // ;follow stop
        builder.then(literal("stop").executes(context -> {
            if (FollowEntityTask.isActive()) {
                FollowEntityTask.stop();
            } else {
                Debug.logMessage("Not following anyone.");
            }
            return SINGLE_SUCCESS;
        }));

        // ;follow <playerName>
        builder.then(argument("playerName", StringArgumentType.word()).executes(context -> {
            String name = StringArgumentType.getString(context, "playerName");
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return SINGLE_SUCCESS;

            Entity target = null;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(name)) {
                    target = p;
                    break;
                }
            }

            if (target == null) {
                Debug.logWarning("Player not found: " + name);
            } else {
                FollowEntityTask.start(target);
            }
            return SINGLE_SUCCESS;
        }));
    }
}
