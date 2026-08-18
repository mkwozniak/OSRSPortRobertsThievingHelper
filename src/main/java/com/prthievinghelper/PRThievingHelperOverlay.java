package com.prthievinghelper;

import com.prthievinghelper.PRThievingHelperPlugin.StallTypes;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class PRThievingHelperOverlay extends Overlay
{
    private final Client client;
    private final PRThievingHelperPlugin plugin;
    private final PRThievingHelperConfig config;

    private final Map<PRThievingHelperPlugin.StallTypes, Color> stallColors = new HashMap<>();

    @Inject
    public PRThievingHelperOverlay(Client client,
                                   PRThievingHelperPlugin plugin,
                                   PRThievingHelperConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if(config.drawBoxes())
        {
            for (StallTypes stall : StallTypes.values()) {
                boolean watched = plugin.STALLS.get(stall).UnwatchedTicksRemaining == 0;
                stallColors.put(stall,
                        watched  ?
                        config.watchedStallColor() : config.unwatchedStallColor());

                renderBox(graphics, plugin.STALLS.get(stall).Position, 1, 2,
                        stallColors.get(stall), watched);
            }
        }

        if (config.enableStallHighlighting())
        {
            // Highlight primary stall with primary color
            TileObject primaryStall = plugin.getPrimaryStallToHighlight();
            StallTypes primaryStallType = plugin.getPrimaryStallType();

            // Highlight secondary stall with secondary color
            TileObject secondaryStall = plugin.getSecondaryStallToHighlight();
            StallTypes secondaryStallType = plugin.getSecondaryStallType();

            if(primaryStall == null || secondaryStall == null) {
                return null;
            }

            if(config.enableTextAttempts())
            {
                renderStallAttempts(graphics, primaryStall, primaryStallType, config.primaryHighlightColor());
                renderStallAttempts(graphics, secondaryStall, secondaryStallType, config.secondaryHighlightColor());
            }

            if (config.highlightOnlyOneStall())
            {
                // Only highlight one stall at a time, prefer primary
                if (plugin.isStallSafeToClick(primaryStallType))
                {
                    renderStallHighlight(graphics, primaryStall, config.primaryHighlightColor());
                }
                else if (plugin.isStallSafeToClick(secondaryStallType))
                {
                    renderStallHighlight(graphics, secondaryStall, config.secondaryHighlightColor());
                }
            }
            else
            {
                // Highlight both stalls when safe
                if (plugin.isStallSafeToClick(primaryStallType))
                {
                    renderStallHighlight(graphics, primaryStall, config.primaryHighlightColor());
                }

                if (plugin.isStallSafeToClick(secondaryStallType))
                {
                    renderStallHighlight(graphics, secondaryStall, config.secondaryHighlightColor());
                }
            }
        }

        float alpha = plugin.getFlashAlpha();
        if (alpha >= 0f)
        {
            Color flashColor = new Color(config.notifierFlashColor().getRed(),
                    config.notifierFlashColor().getGreen(),
                    config.notifierFlashColor().getBlue(),
                    (int)(alpha * config.notifierFlashStrength()));
            graphics.setColor(flashColor);
            graphics.fillRect(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
        }

        return null;
    }

    private void renderBox(Graphics2D graphics, WorldPoint point, int width, int height,
                           Color color, boolean watched)
    {
        if (point == null) { return; }

        // Get the corners of the range square
        WorldPoint topLeft = new WorldPoint(
                point.getX() - width,
                point.getY() + height,
                point.getPlane()
        );
        WorldPoint topRight = new WorldPoint(
                point.getX() + width,
                point.getY() + height,
                point.getPlane()
        );
        WorldPoint bottomLeft = new WorldPoint(
                point.getX() - width,
                point.getY() - height,
                point.getPlane()
        );
        WorldPoint bottomRight = new WorldPoint(
                point.getX() + width,
                point.getY() - height,
                point.getPlane()
        );

        // Convert to local points
        LocalPoint topLeftLocal = LocalPoint.fromWorld(client, topLeft);
        LocalPoint topRightLocal = LocalPoint.fromWorld(client, topRight);
        LocalPoint bottomLeftLocal = LocalPoint.fromWorld(client, bottomLeft);
        LocalPoint bottomRightLocal = LocalPoint.fromWorld(client, bottomRight);

        if (topLeftLocal == null
                || topRightLocal == null
                || bottomLeftLocal == null
                || bottomRightLocal == null)
        {
            return;
        }

        renderSquareFromPoints(graphics,
                topLeftLocal, topRightLocal, bottomLeftLocal, bottomRightLocal,
                color, watched ? config.watchedStallBorderColor() : config.unwatchedStallBorderColor());
    }

    private void renderSquareFromPoints(Graphics2D graphics,
                                        LocalPoint topLeft, LocalPoint topRight,
                                        LocalPoint bottomLeft, LocalPoint bottomRight,
                                        Color color, Color strokeColor)
    {
        Polygon topLeftPoly = Perspective.getCanvasTilePoly(client, topLeft);
        Polygon topRightPoly = Perspective.getCanvasTilePoly(client, topRight);
        Polygon bottomLeftPoly = Perspective.getCanvasTilePoly(client, bottomLeft);
        Polygon bottomRightPoly = Perspective.getCanvasTilePoly(client, bottomRight);

        if (topLeftPoly == null
                || topRightPoly == null
                || bottomLeftPoly == null
                || bottomRightPoly == null)
        {
            return;
        }

        Polygon square = new Polygon();
        square.addPoint(topLeftPoly.xpoints[0], topLeftPoly.ypoints[0]);
        square.addPoint(topRightPoly.xpoints[1], topRightPoly.ypoints[1]);
        square.addPoint(bottomRightPoly.xpoints[2], bottomRightPoly.ypoints[2]);
        square.addPoint(bottomLeftPoly.xpoints[3], bottomLeftPoly.ypoints[3]);

        graphics.setColor(color);
        graphics.fillPolygon(square);

        graphics.setColor(strokeColor);
        graphics.setStroke(new BasicStroke(2));
        graphics.drawPolygon(square);
    }

    private void renderStallHighlight(Graphics2D graphics, TileObject object, Color highlightColor)
    {
        if(object == null)
            return;

        // Draw clickbox/outline
        Shape objectClickbox = object.getClickbox();
        if (objectClickbox != null)
        {
            // Draw fill
            graphics.setColor(new Color(highlightColor.getRed(),
                    highlightColor.getGreen(),
                    highlightColor.getBlue(),
                    20)); // Semi-transparent fill
            graphics.fill(objectClickbox);

            // Draw outline
            graphics.setColor(new Color(highlightColor.getRed(),
                    highlightColor.getGreen(),
                    highlightColor.getBlue(),
                    highlightColor.getAlpha()));
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(objectClickbox);
        }
    }

    private void renderStallAttempts(Graphics2D graphics, TileObject object,
                                     StallTypes stallType, Color highlightColor)
    {
        if(object == null)
            return;

        LocalPoint localPoint = object.getLocalLocation();
        if (localPoint != null)
        {
            int ticksLeft = plugin.GetStallUnwatchedTicksRemaining(stallType);
            Color color = highlightColor;
            String msg = "" + plugin.GetStallUnwatchedTicksRemaining(stallType);

            if(ticksLeft == 0)
            {
                msg = "UNSAFE";
                color = Color.RED;
            }

            Point textLocation = Perspective.getCanvasTextLocation(
                    client,
                    graphics,
                    localPoint,
                    msg,
                    200 // Z-offset to position text above the object
            );

            if (textLocation != null)
            {
                // Draw text shadow for better visibility
                graphics.setFont(new Font("Arial", Font.BOLD, 16));
                graphics.setColor(color);
                graphics.drawString(msg, textLocation.getX() + 1, textLocation.getY() + 1);

                // Draw the actual text
                graphics.setColor(color);
                graphics.drawString(msg, textLocation.getX(), textLocation.getY());
            }
        }
    }
}
