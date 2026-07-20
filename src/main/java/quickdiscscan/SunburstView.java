package quickdiscscan;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static quickdiscscan.I18n.text;

final class SunburstView extends Region {
    private static final Color[] PALETTE = {
            Color.web("#3478f6"), Color.web("#7b61ff"), Color.web("#ef476f"),
            Color.web("#f59e0b"), Color.web("#10b981"), Color.web("#06b6d4"),
            Color.web("#ec4899"), Color.web("#84cc16")
    };
    private static final int LEVELS = 4;
    private static final int MAX_SEGMENTS = 2_500;
    private static final double MIN_ANGLE = 0.0025;

    private final Canvas canvas = new Canvas();
    private final Tooltip tooltip = new Tooltip();
    private final ArrayList<Segment> segments = new ArrayList<>();
    private DiskScanner.ScanNode root;
    private DiskScanner.ScanNode focus;
    private DiskScanner.SizeBasis basis = DiskScanner.SizeBasis.PHYSICAL;
    private Consumer<DiskScanner.ScanNode> focusListener = ignored -> {};
    private double centerX;
    private double centerY;
    private double innerRadius;
    private double ringWidth;

    private record Segment(DiskScanner.ScanNode node, String label, long bytes,
                           double start, double end, double inner, double outer, Color color) {}

    SunburstView() {
        getChildren().add(canvas);
        setMinSize(260, 260);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener(ignored -> draw());
        heightProperty().addListener(ignored -> draw());

        canvas.setOnMouseMoved(event -> {
            Segment segment = hit(event.getX(), event.getY());
            boolean center = Math.hypot(event.getX() - centerX, event.getY() - centerY) < innerRadius;
            setCursor(segment != null && segment.node != null && segment.node.directory() || center
                    ? Cursor.HAND : Cursor.DEFAULT);
            if (segment == null) {
                tooltip.hide();
                return;
            }
            String suffix = segment.node != null && segment.node.offlineFiles() > 0
                    ? "\n" + segment.node.offlineFiles() + " offline" : "";
            tooltip.setText(segment.label + "\n" + ByteFormat.bytes(segment.bytes) + suffix);
            if (!tooltip.isShowing()) {
                tooltip.show(canvas, event.getScreenX() + 14, event.getScreenY() + 10);
            } else {
                tooltip.setAnchorX(event.getScreenX() + 14);
                tooltip.setAnchorY(event.getScreenY() + 10);
            }
        });
        canvas.setOnMouseExited(event -> tooltip.hide());
        canvas.setOnMouseClicked(event -> {
            double radius = Math.hypot(event.getX() - centerX, event.getY() - centerY);
            if (radius < innerRadius && focus != null && focus.parent() != null) {
                setFocus(focus.parent());
                return;
            }
            Segment segment = hit(event.getX(), event.getY());
            if (segment != null && segment.node != null && segment.node.directory()
                    && !segment.node.children().isEmpty()) {
                setFocus(segment.node);
            }
        });
    }

    void setData(DiskScanner.ScanNode newRoot, DiskScanner.SizeBasis newBasis) {
        root = newRoot;
        basis = newBasis;
        if (focus == null || !isDescendantOf(focus, root)) {
            focus = root;
        }
        draw();
    }

    void setFocus(DiskScanner.ScanNode node) {
        if (node == null) {
            return;
        }
        focus = node;
        draw();
        focusListener.accept(node);
    }

    DiskScanner.ScanNode focus() {
        return focus;
    }

    void setOnFocusChanged(Consumer<DiskScanner.ScanNode> listener) {
        focusListener = listener == null ? ignored -> {} : listener;
    }

    private void draw() {
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        graphics.clearRect(0, 0, width, height);
        segments.clear();
        if (focus == null || width < 20 || height < 20) {
            return;
        }

        centerX = width / 2;
        centerY = height / 2;
        double radius = Math.max(10, Math.min(width, height) / 2 - 18);
        innerRadius = radius * 0.22;
        ringWidth = (radius - innerRadius) / LEVELS;

        List<DiskScanner.ScanNode> firstLevel = childrenWithSize(focus);
        long total = firstLevel.stream().mapToLong(basis::bytes).sum();
        if (total == 0) {
            total = basis.bytes(focus);
        }
        if (total > 0) {
            layoutChildren(focus, firstLevel, 0, Math.PI * 2, 0, total, 0);
            for (Segment segment : segments) {
                drawSegment(graphics, segment);
            }
        }

        graphics.setFill(Color.web("#202633"));
        graphics.fillOval(centerX - innerRadius + 2, centerY - innerRadius + 2,
                innerRadius * 2 - 4, innerRadius * 2 - 4);
        graphics.setFill(Color.WHITE);
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.setTextBaseline(VPos.CENTER);
        graphics.setFont(Font.font("System", FontWeight.BOLD, Math.max(11, innerRadius * 0.16)));
        String name = focus.name();
        if (name.length() > 18) {
            name = name.substring(0, 16) + "…";
        }
        graphics.fillText(name, centerX, centerY - 8, innerRadius * 1.65);
        graphics.setFont(Font.font("System", Math.max(10, innerRadius * 0.13)));
        graphics.setFill(Color.web("#c8ccd4"));
        graphics.fillText(ByteFormat.bytes(Math.max(total, basis.bytes(focus))),
                centerX, centerY + 13, innerRadius * 1.65);
    }

    private void layoutChildren(DiskScanner.ScanNode parent, List<DiskScanner.ScanNode> children,
                                double start, double end, int depth, long total, int paletteIndex) {
        if (depth >= LEVELS || children.isEmpty() || total <= 0 || segments.size() >= MAX_SEGMENTS) {
            return;
        }
        double cursor = start;
        long otherBytes = 0;
        double otherStart = end;
        int index = 0;
        for (DiskScanner.ScanNode child : children) {
            long bytes = basis.bytes(child);
            double angle = (end - start) * bytes / total;
            if (angle < MIN_ANGLE || segments.size() >= MAX_SEGMENTS) {
                if (otherBytes == 0) {
                    otherStart = cursor;
                }
                otherBytes += bytes;
                cursor += angle;
                continue;
            }

            int childPalette = depth == 0 ? index % PALETTE.length : paletteIndex;
            Color color = shade(PALETTE[childPalette], depth);
            Segment segment = new Segment(child, child.name(), bytes, cursor, cursor + angle,
                    innerRadius + depth * ringWidth, innerRadius + (depth + 1) * ringWidth, color);
            segments.add(segment);
            if (child.directory() && depth + 1 < LEVELS) {
                List<DiskScanner.ScanNode> grandchildren = childrenWithSize(child);
                long childTotal = grandchildren.stream().mapToLong(basis::bytes).sum();
                layoutChildren(child, grandchildren, cursor, cursor + angle, depth + 1,
                        childTotal, childPalette);
            }
            cursor += angle;
            index++;
        }
        if (otherBytes > 0 && otherStart < end) {
            segments.add(new Segment(null, text("Andere", "Other"), otherBytes, otherStart, end,
                    innerRadius + depth * ringWidth, innerRadius + (depth + 1) * ringWidth,
                    Color.web("#6b7280")));
        }
    }

    private List<DiskScanner.ScanNode> childrenWithSize(DiskScanner.ScanNode node) {
        return node.sortedChildren(basis).stream().filter(child -> basis.bytes(child) > 0).toList();
    }

    private void drawSegment(GraphicsContext graphics, Segment segment) {
        double gap = 0.008;
        double start = segment.start + gap;
        double end = segment.end - gap;
        if (end <= start) {
            start = segment.start;
            end = segment.end;
        }
        int steps = Math.max(2, Math.min(80,
                (int) Math.ceil((end - start) * segment.outer / 7)));
        graphics.beginPath();
        graphics.moveTo(centerX + Math.cos(start) * segment.inner,
                centerY + Math.sin(start) * segment.inner);
        graphics.lineTo(centerX + Math.cos(start) * segment.outer,
                centerY + Math.sin(start) * segment.outer);
        for (int index = 1; index <= steps; index++) {
            double angle = start + (end - start) * index / steps;
            graphics.lineTo(centerX + Math.cos(angle) * segment.outer,
                    centerY + Math.sin(angle) * segment.outer);
        }
        for (int index = steps; index >= 0; index--) {
            double angle = start + (end - start) * index / steps;
            graphics.lineTo(centerX + Math.cos(angle) * segment.inner,
                    centerY + Math.sin(angle) * segment.inner);
        }
        graphics.closePath();
        graphics.setFill(segment.color);
        graphics.fill();
        graphics.setStroke(Color.color(1, 1, 1, 0.16));
        graphics.setLineWidth(0.7);
        graphics.stroke();
    }

    private Segment hit(double x, double y) {
        double dx = x - centerX;
        double dy = y - centerY;
        double radius = Math.hypot(dx, dy);
        if (radius < innerRadius) {
            return null;
        }
        double angle = Math.atan2(dy, dx);
        if (angle < 0) {
            angle += Math.PI * 2;
        }
        for (int index = segments.size() - 1; index >= 0; index--) {
            Segment segment = segments.get(index);
            if (radius >= segment.inner && radius <= segment.outer
                    && angle >= segment.start && angle <= segment.end) {
                return segment;
            }
        }
        return null;
    }

    private static Color shade(Color color, int depth) {
        return color.deriveColor(0, Math.max(0.72, 1 - depth * 0.05),
                Math.max(0.62, 1 - depth * 0.11), 0.94);
    }

    private static boolean isDescendantOf(DiskScanner.ScanNode node, DiskScanner.ScanNode ancestor) {
        for (DiskScanner.ScanNode current = node; current != null; current = current.parent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }
}
