package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.task.FollowPlayerTask;
import net.minecraft.command.CommandSource;

public class FollowPlayerCommand extends Command {

    public FollowPlayerCommand(TungstenMod mod) throws CommandException {
        super("followPlayer", "Follow a player by name (re-discovers if they disappear)", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // ;followPlayer stop
        builder.then(literal("stop").executes(context -> {
            if (FollowPlayerTask.isActive()) {
                FollowPlayerTask.stop();
            } else {
                Debug.logMessage("Not following anyone.");
            }
            return SINGLE_SUCCESS;
        }));

        // ;followPlayer <name>
        builder.then(argument("name", StringArgumentType.word()).executes(context -> {
            String name = StringArgumentType.getString(context, "name");
            FollowPlayerTask.start(name);
            return SINGLE_SUCCESS;
        }));
    }
}
