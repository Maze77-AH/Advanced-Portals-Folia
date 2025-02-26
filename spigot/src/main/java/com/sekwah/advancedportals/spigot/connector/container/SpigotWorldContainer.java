package com.sekwah.advancedportals.spigot.connector.container;

import com.sekwah.advancedportals.core.connector.containers.WorldContainer;
import com.sekwah.advancedportals.core.data.BlockAxis;
import com.sekwah.advancedportals.core.portal.AdvancedPortal;
import com.sekwah.advancedportals.core.serializeddata.BlockLocation;
import java.awt.*;
import java.lang.reflect.Method;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.EndGateway;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

public class SpigotWorldContainer implements WorldContainer {
    private final World world;

    // True if setAge(long) is present on EndGateway, discovered at runtime.
    private static boolean endGatewaySetAgeExists;
    // Keep a cached reflection Method reference if you want:
    private static Method endGatewaySetAgeMethod;

    static {
        try {
            Method method = EndGateway.class.getMethod("setAge", long.class);
            if (method != null) {
                endGatewaySetAgeExists = true;
                endGatewaySetAgeMethod = method;
            }
        } catch (NoSuchMethodException ignored) {
            endGatewaySetAgeExists = false;
        }
    }

    public SpigotWorldContainer(World world) {
        this.world = world;
    }

    public void setBlock(BlockLocation location, String material) {
        Material mat = Material.getMaterial(material, false);
        if (mat != null)
            this.world
                .getBlockAt(location.getPosX(), location.getPosY(),
                            location.getPosZ())
                .setType(mat);
    }

    public String getBlock(BlockLocation location) {
        return this.world
            .getBlockAt(location.getPosX(), location.getPosY(),
                        location.getPosZ())
            .getType()
            .toString();
    }

    @Override
    public BlockAxis getBlockAxis(BlockLocation location) {
        Block block = world.getBlockAt(location.getPosX(), location.getPosY(),
                                       location.getPosZ());
        BlockData matData = block.getState().getBlockData();
        if (matData instanceof Orientable) {
            Orientable rotatable = (Orientable) matData;
            try {
                return BlockAxis.valueOf(rotatable.getAxis().toString());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void setBlockAxis(BlockLocation location, BlockAxis axis) {
        Block block = world.getBlockAt(location.getPosX(), location.getPosY(),
                                       location.getPosZ());
        BlockData matData = block.getState().getBlockData();
        if (matData instanceof Orientable) {
            Orientable rotatable = (Orientable) matData;
            rotatable.setAxis(Axis.valueOf(axis.toString()));
            block.setBlockData(rotatable);
        }
    }

    @Override
    public void disableBeacon(BlockLocation location) {
        if (!endGatewaySetAgeExists) return;
        Block block = world.getBlockAt(location.getPosX(), location.getPosY(), location.getPosZ());
        if (block.getType() == Material.END_GATEWAY && block.getState() instanceof EndGateway) {
            EndGateway endGateway = (EndGateway) block.getState();
            try {
                // Reflectively call setAge(Long.MIN_VALUE)
                endGatewaySetAgeMethod.invoke(endGateway, Long.MIN_VALUE);
                endGateway.update();
            } catch (Exception ex) {
                // log or ignore
            }
        }
    }

    @Override
    public void disableBeacon(AdvancedPortal portal) {
        if (!endGatewaySetAgeExists) return;
        BlockLocation maxLoc = portal.getMaxLoc();
        BlockLocation minLoc = portal.getMinLoc();

        for (int x = minLoc.getPosX(); x <= maxLoc.getPosX(); x++) {
            for (int y = minLoc.getPosY(); y <= maxLoc.getPosY(); y++) {
                for (int z = minLoc.getPosZ(); z <= maxLoc.getPosZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.END_GATEWAY && block.getState() instanceof EndGateway) {
                        EndGateway endGateway = (EndGateway) block.getState();
                        try {
                            endGatewaySetAgeMethod.invoke(endGateway, Long.MIN_VALUE);
                            endGateway.update();
                        } catch (Exception ex) {
                            // log or ignore
                        }
                    }
                }
            }
        }
    }
}
