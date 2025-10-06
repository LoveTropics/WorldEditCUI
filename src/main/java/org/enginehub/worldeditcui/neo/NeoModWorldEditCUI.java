/*
 * Copyright (c) 2011-2024 WorldEditCUI team and contributors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.enginehub.worldeditcui.neo;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.enginehub.worldeditcui.WorldEditCUI;
import org.enginehub.worldeditcui.config.CUIConfiguration;
import org.enginehub.worldeditcui.event.listeners.CUIListenerChannel;
import org.enginehub.worldeditcui.event.listeners.CUIListenerWorldRender;
import org.enginehub.worldeditcui.gui.CUIConfigPanel;
import org.enginehub.worldeditcui.protocol.CUIPacket;
import org.enginehub.worldeditcui.protocol.CUIPacketHandler;
import org.enginehub.worldeditcui.render.PipelineProvider;
import org.enginehub.worldeditcui.render.VanillaPipelineProvider;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.util.List;

/**
 * Neo mod entrypoint
 *
 * @author Mark Vainomaa
 */
@Mod(value = NeoModWorldEditCUI.MOD_ID, dist = Dist.CLIENT)
public final class NeoModWorldEditCUI {
    private static final int DELAYED_HELO_TICKS = 10;

    public static final String MOD_ID = "worldeditcui";
    private static NeoModWorldEditCUI instance;

    private static final String KEYBIND_CATEGORY_WECUI = "key.categories.worldeditcui";
    private final KeyMapping keyBindToggleUI = key("toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindClearSel = key("clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindChunkBorder = key("chunk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);

    private static final List<PipelineProvider> RENDER_PIPELINES = List.of(
            new VanillaPipelineProvider()
    );

    private WorldEditCUI controller;
    private CUIListenerWorldRender worldRenderListener;
    private CUIListenerChannel channelListener;

    private Level lastWorld;
    private LocalPlayer lastPlayer;

    private boolean visible = true;
    private int delayedHelo = 0;

    /**
     * Register a key binding
     *
     * @param name id, will be used as a localization key under {@code key.worldeditcui.<name>}
     * @param type type
     * @param code default value
     * @return new, registered keybinding in the mod category
     */
    private static KeyMapping key(final String name, final InputConstants.Type type, final int code) {
        return new KeyMapping("key." + MOD_ID + '.' + name, type, code, KEYBIND_CATEGORY_WECUI);
    }

    public NeoModWorldEditCUI(final IEventBus modBus, final ModContainer modContainer) {
        if (Boolean.getBoolean("wecui.debug.mixinaudit")) {
            MixinEnvironment.getCurrentEnvironment().audit();
        }

        instance = this;

        NeoForge.EVENT_BUS.register(this);
        modBus.addListener(this::registerKeyMapping);

        CUINetworking.subscribeToCuiPacket(this::onPluginMessage);

        modContainer.registerExtensionPoint(IConfigScreenFactory.class, (c, parentScreen) -> new CUIConfigPanel(parentScreen, controller.getConfiguration()));
    }

    private void registerKeyMapping(final RegisterKeyMappingsEvent event) {
        event.register(keyBindToggleUI);
        event.register(keyBindClearSel);
        event.register(keyBindChunkBorder);
    }

    @SubscribeEvent
    public void onTick(final ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final CUIConfiguration config = this.controller.getConfiguration();
        final boolean inGame = mc.player != null;
        final boolean clock = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) > 0;

        if (inGame && mc.screen == null) {
            while (this.keyBindToggleUI.consumeClick()) {
                this.visible = !this.visible;
            }

            while (this.keyBindClearSel.consumeClick()) {
                if (mc.player != null) {
                    mc.player.connection.sendUnattendedCommand("/sel", null);
                }

                if (config.isClearAllOnKey()) {
                    this.controller.clearRegions();
                }
            }

            while (this.keyBindChunkBorder.consumeClick()) {
                this.controller.toggleChunkBorders();
            }
        }

        if (inGame && clock && this.controller != null) {
            if (mc.level != this.lastWorld || mc.player != this.lastPlayer) {
                this.lastWorld = mc.level;
                this.lastPlayer = mc.player;

                this.controller.getDebugger().debug("World change detected, sending new handshake");
                this.controller.clear();
                this.helo(mc.getConnection());
                this.delayedHelo = NeoModWorldEditCUI.DELAYED_HELO_TICKS;
                if (mc.player != null && config.isPromiscuous()) {
                    mc.player.connection.sendUnattendedCommand("we cui", null); // Tricks WE to send the current selection
                }
            }

            if (this.delayedHelo > 0) {
                this.delayedHelo--;
                if (this.delayedHelo == 0) {
                    this.helo(mc.getConnection());
                }
            }
        }
    }

    private void onPluginMessage(final CUIPacket payload, final CUIPacketHandler.PacketContext ctx) {
        try {
            ctx.workExecutor().execute(() -> this.channelListener.onMessage(payload));
        } catch (final Exception ex) {
            this.getController().getDebugger().info("Error decoding payload from server", ex);
        }
    }


    @SubscribeEvent
    public void onGameInitDone(final ClientStartedEvent event) {
        final Minecraft client = Minecraft.getInstance();
        this.controller = new WorldEditCUI();
        this.controller.initialise(client);
        this.worldRenderListener = new CUIListenerWorldRender(this.controller, client, RENDER_PIPELINES);
        this.channelListener = new CUIListenerChannel(this.controller);
    }

    @SubscribeEvent
    public void onJoinGame(final ClientPlayerNetworkEvent.LoggingIn event) {
        this.visible = true;
        this.controller.getDebugger().debug("Joined game, sending initial handshake");
        this.helo(event.getPlayer().connection);
    }

    @SubscribeEvent
    public void onPostRenderEntities(final RenderLevelStageEvent.AfterWeather event) {
        if (this.visible) {
            try {
                RenderSystem.getModelViewStack().pushMatrix();
                RenderSystem.getModelViewStack().mul(event.getPoseStack().last().pose());
                this.worldRenderListener.onRender(event.getPartialTick().getRealtimeDeltaTicks());
            } finally {
                RenderSystem.getModelViewStack().popMatrix();
            }
        }
    }

    private void helo(final ClientPacketListener handler) {
        CUINetworking.send(new CUIPacket("v", CUIPacket.protocolVersion()));
    }

    public WorldEditCUI getController()
    {
        return this.controller;
    }

    public static NeoModWorldEditCUI getInstance() {
        return instance;
    }
}
