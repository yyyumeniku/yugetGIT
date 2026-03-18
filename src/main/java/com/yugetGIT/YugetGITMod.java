package com.yugetGIT;

import com.yugetGIT.yugetgit.Tags;
import com.yugetGIT.commands.YuCommand;
import com.yugetGIT.events.ChunkDirtyTrackerHandler;
import com.yugetGIT.events.WorldSaveHandler;
import com.yugetGIT.ui.SaveProgressOverlay;
import com.yugetGIT.ui.VisualDiffOverlay;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, guiFactory = "com.yugetGIT.ui.YugetGITGuiFactory")
public class YugetGITMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);
    private final WorldSaveHandler worldSaveHandler = new WorldSaveHandler();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hello From {}!", Tags.MOD_NAME);
    }
    
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(worldSaveHandler);
        MinecraftForge.EVENT_BUS.register(new ChunkDirtyTrackerHandler());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new SaveProgressOverlay());
            MinecraftForge.EVENT_BUS.register(new VisualDiffOverlay());
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new YuCommand());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        worldSaveHandler.handleServerStopping(FMLCommonHandler.instance().getMinecraftServerInstance());
    }
}
