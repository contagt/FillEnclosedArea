package org.openstreetmap.josm.plugins.fillenclosedarea;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * MapMode: click inside a region bounded by existing OSM way geometry to
 * create a new closed way that reuses exactly the existing OSM nodes.
 *
 * <p>The boundary may be assembled from portions of multiple existing ways.
 * Connectivity is determined exclusively by shared OSM nodes. Geometric
 * crossings which do not contain a shared OSM node are NOT treated as
 * connections.</p>
 *
 * <p>The algorithm does not enumerate all simple cycles. Instead it constructs
 * the planar faces of the graph using directed half-edges. This is important
 * for performance on large or dense OSM datasets, where enumerating all
 * simple cycles can become combinatorially expensive.</p>
 *
 * <p>No snapping, buffering, union, automatic intersection-node creation,
 * or topology repair is performed.</p>
 */
public class FillEnclosedAreaAction extends MapMode {

    private static final long serialVersionUID = 1L;

    public FillEnclosedAreaAction() {
        super(tr("Fill Enclosed Area"),
                "fillenclosedarea",
                tr("Create a closed way from existing boundary geometry surrounding the clicked point"),
                Shortcut.registerShortcut(
                        "mapmode:fillenclosedarea",
                        tr("Mode: {0}", tr("Fill Enclosed Area")),
                        KeyEvent.VK_F,
                        Shortcut.ALT_CTRL_SHIFT),
                Cursor.getDefaultCursor());
    }

    @Override
    public void enterMode() {
        super.enterMode();

        if (MainApplication.isDisplayingMapView()) {
            MainApplication.getMap().mapView.addMouseListener(this);
        }
    }

    @Override
    public void exitMode() {
        super.exitMode();

        if (MainApplication.isDisplayingMapView()) {
            MainApplication.getMap().mapView.removeMouseListener(this);
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        if (!MainApplication.isDisplayingMapView()) {
            return;
        }

        DataSet ds = getLayerManager().getEditDataSet();

        if (ds == null) {
            return;
        }

        EastNorth clickEN =
                MainApplication.getMap().mapView.getEastNorth(
                        e.getX(),
                        e.getY());

        if (clickEN == null) {
            return;
        }

        try {
            fillAt(ds, clickEN);
        } catch (RuntimeException ex) {
            Logging.error(ex);

            String message = ex.getMessage();

            if (message == null || message.isEmpty()) {
                message = ex.getClass().getSimpleName();
            }

            notify(
                    tr("Fill Enclosed Area failed: {0}", message),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Main fill operation.
     */
    private void fillAt(DataSet ds, EastNorth clickEN) {
        GeometryFactory gf = new GeometryFactory();

        /*
         * Build one graph from all usable ways in the edit layer.
         *
         * A graph edge represents one actual pair of consecutive OSM nodes
         * from an existing way.
         */
        BoundaryGraph graph = buildBoundaryGraph(ds);

        if (graph.edges.isEmpty()) {
            notify(
                    tr("No boundary geometry found in this layer."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        double clickX = clickEN.east();
        double clickY = clickEN.north();

        if (!Double.isFinite(clickX) || !Double.isFinite(clickY)) {
            notify(
                    tr("The clicked position is invalid. Nothing was created."),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        Point clickPoint =
                gf.createPoint(new Coordinate(clickX, clickY));

        /*
         * Extract planar faces.
         *
         * This is the critical performance improvement over the previous
         * all-simple-cycles implementation.
         */
        List<List<Node>> faces = findFaces(graph);

        if (faces.isEmpty()) {
            notify(
                    tr("The clicked point is not inside a closed boundary. "
                            + "Nothing was created."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        /*
         * Find the smallest valid face containing the clicked point.
         */
        List<Node> bestFace = null;
        Polygon bestPolygon = null;
        double bestArea = Double.MAX_VALUE;

        for (List<Node> face : faces) {
            Polygon polygon = createPolygon(gf, face);

            if (polygon == null) {
                continue;
            }

            /*
             * Do not repair invalid geometry.
             *
             * In particular, do NOT use buffer(0), union, snap, etc.
             * Those operations can introduce vertices which do not correspond
             * to actual OSM nodes.
             */
            if (!polygon.isValid()) {
                Logging.warn(
                        "Fill Enclosed Area: ignoring invalid planar face.");
                continue;
            }

            double area = polygon.getArea();

            if (!Double.isFinite(area) || area <= 0.0) {
                continue;
            }

            boolean covers;

            try {
                covers = polygon.covers(clickPoint);
            } catch (TopologyException ex) {
                Logging.warn(
                        "Fill Enclosed Area: topology exception while "
                                + "testing face: " + ex.getMessage());
                continue;
            } catch (RuntimeException ex) {
                Logging.warn(
                        "Fill Enclosed Area: could not test face: "
                                + ex.getMessage());
                continue;
            }

            if (!covers) {
                continue;
            }

            /*
             * The smallest containing face is the innermost region.
             */
            if (area < bestArea) {
                bestArea = area;
                bestFace = face;
                bestPolygon = polygon;
            }
        }

        if (bestFace == null || bestPolygon == null) {
            notify(
                    tr("The clicked point is not inside a valid closed "
                            + "boundary. Nothing was created."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (bestFace.size() < 3) {
            notify(
                    tr("The enclosing boundary is degenerate. "
                            + "Nothing was created."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        /*
         * The face already consists of the actual OSM Node objects from the
         * graph. Therefore there is no coordinate -> node lookup here.
         */
        List<Node> wayNodes = new ArrayList<>(bestFace);

        /*
         * A valid simple face should not repeat a node.
         */
        if (containsDuplicateNodes(wayNodes)) {
            Logging.warn(
                    "Fill Enclosed Area: selected face contains repeated "
                            + "OSM nodes.");

            notify(
                    tr("The enclosing boundary contains repeated nodes. "
                            + "Nothing was created."),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        /*
         * Close the new OSM way.
         */
        wayNodes.add(wayNodes.get(0));

        /*
         * Avoid creating an exact duplicate of an existing closed way.
         */
        Way existing = findExistingClosedWay(ds, wayNodes);

        if (existing != null) {
            ds.setSelected(existing);

            notify(
                    tr("This area already exists as a closed way; selected "
                            + "it instead of duplicating it."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        /*
         * Create the new way using only existing OSM nodes.
         */
        Way newWay = new Way();
        newWay.setNodes(wayNodes);

        UndoRedoHandler.getInstance().add(
                new AddCommand(ds, newWay));

        ds.setSelected(newWay);

        notify(
                tr("Created a closed way from {0} existing nodes.",
                        wayNodes.size() - 1),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Builds an undirected graph from every consecutive pair of nodes in
     * every usable existing way.
     *
     * <p>Different ways can contribute different edges to the same graph.
     * This is what allows a resulting boundary to be assembled from multiple
     * existing geometries.</p>
     *
     * <p>Importantly, nodes are used as graph vertices directly. Two ways are
     * connected only if they reference the same OSM Node object.</p>
     */
    private BoundaryGraph buildBoundaryGraph(DataSet ds) {
        BoundaryGraph graph = new BoundaryGraph();

        for (Way way : ds.getWays()) {
            if (way.isDeleted()
                    || way.isIncomplete()
                    || way.getNodesCount() < 2) {
                continue;
            }

            List<Node> nodes = way.getNodes();

            for (int i = 0; i < nodes.size() - 1; i++) {
                Node a = nodes.get(i);
                Node b = nodes.get(i + 1);

                if (a == null || b == null) {
                    continue;
                }

                if (a.isDeleted() || b.isDeleted()) {
                    continue;
                }

                /*
                 * Ignore zero-length graph edges.
                 */
                if (a == b) {
                    continue;
                }

                EastNorth aEN = a.getEastNorth();
                EastNorth bEN = b.getEastNorth();

                if (aEN == null || bEN == null) {
                    continue;
                }

                if (!finite(aEN) || !finite(bEN)) {
                    Logging.warn(
                            "Fill Enclosed Area: ignoring segment with "
                                    + "non-finite coordinates in way "
                                    + way.getId());
                    continue;
                }

                graph.addEdge(a, b);
            }
        }

        return graph;
    }

    /**
     * Finds all planar faces of the graph using a half-edge traversal.
     *
     * <p>This runs in approximately O(E log E), dominated by sorting the
     * outgoing edges around each node.</p>
     *
     * <p>It does NOT enumerate all simple cycles.</p>
     */
    private List<List<Node>> findFaces(BoundaryGraph graph) {
        List<HalfEdge> halfEdges = new ArrayList<>();

        /*
         * Every undirected edge becomes two directed half-edges.
         */
        for (Edge edge : graph.edges) {
            HalfEdge ab =
                    new HalfEdge(edge.a, edge.b);

            HalfEdge ba =
                    new HalfEdge(edge.b, edge.a);

            ab.reverse = ba;
            ba.reverse = ab;

            halfEdges.add(ab);
            halfEdges.add(ba);
        }

        /*
         * Group outgoing half-edges by their source node.
         */
        Map<Node, List<HalfEdge>> outgoing = new HashMap<>();

        for (HalfEdge edge : halfEdges) {
            outgoing
                    .computeIfAbsent(
                            edge.from,
                            ignored -> new ArrayList<>())
                    .add(edge);
        }

        /*
         * Sort every node's outgoing edges counter-clockwise.
         */
        for (Map.Entry<Node, List<HalfEdge>> entry
                : outgoing.entrySet()) {

            Node node = entry.getKey();

            entry.getValue().sort(
                    Comparator.comparingDouble(
                            edge -> angle(node, edge.to)));
        }

        /*
         * Establish the "next" half-edge for every directed edge.
         *
         * At the destination node, locate the reverse edge and take the
         * immediately clockwise edge. This keeps the traversed face on the
         * left-hand side of the directed edge.
         */
        for (HalfEdge edge : halfEdges) {
            List<HalfEdge> atDestination =
                    outgoing.get(edge.to);

            if (atDestination == null
                    || atDestination.isEmpty()) {
                continue;
            }

            int reverseIndex =
                    atDestination.indexOf(edge.reverse);

            if (reverseIndex < 0) {
                continue;
            }

            int nextIndex =
                    (reverseIndex - 1
                            + atDestination.size())
                            % atDestination.size();

            edge.next =
                    atDestination.get(nextIndex);
        }

        /*
         * Traverse every half-edge once.
         */
        Set<HalfEdge> visited = new HashSet<>();

        List<List<Node>> faces = new ArrayList<>();

        for (HalfEdge start : halfEdges) {
            if (visited.contains(start)) {
                continue;
            }

            if (start.next == null) {
                continue;
            }

            List<Node> face = new ArrayList<>();

            HalfEdge current = start;
            boolean closed = false;

            while (current != null
                    && !visited.contains(current)) {

                visited.add(current);

                face.add(current.from);

                current = current.next;

                if (current == start) {
                    closed = true;
                    break;
                }
            }

            if (!closed) {
                continue;
            }

            if (face.size() < 3) {
                continue;
            }

            /*
             * A face with a repeated vertex is not a simple polygon and is
             * not suitable for creating the resulting OSM way.
             */
            if (containsDuplicateNodes(face)) {
                continue;
            }

            faces.add(face);
        }

        return faces;
    }

    /**
     * Returns the polar angle of the directed segment node -> target.
     */
    private static double angle(Node node, Node target) {
        EastNorth a = node.getEastNorth();
        EastNorth b = target.getEastNorth();

        return Math.atan2(
                b.north() - a.north(),
                b.east() - a.east());
    }

    /**
     * Creates a JTS polygon from an OSM-node face.
     *
     * <p>All coordinates originate directly from existing OSM nodes.</p>
     */
    private Polygon createPolygon(
            GeometryFactory gf,
            List<Node> face) {

        if (face == null || face.size() < 3) {
            return null;
        }

        Coordinate[] coordinates =
                new Coordinate[face.size() + 1];

        for (int i = 0; i < face.size(); i++) {
            Node node = face.get(i);

            if (node == null) {
                return null;
            }

            EastNorth en = node.getEastNorth();

            if (en == null || !finite(en)) {
                return null;
            }

            coordinates[i] =
                    new Coordinate(
                            en.east(),
                            en.north());
        }

        /*
         * Explicitly close the ring.
         */
        coordinates[coordinates.length - 1] =
                new Coordinate(
                        coordinates[0].x,
                        coordinates[0].y);

        try {
            LinearRing ring =
                    gf.createLinearRing(coordinates);

            Polygon polygon =
                    gf.createPolygon(ring);

            if (polygon.isEmpty()) {
                return null;
            }

            return polygon;

        } catch (RuntimeException ex) {
            Logging.warn(
                    "Fill Enclosed Area: could not create polygon from "
                            + "face: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Checks whether an EastNorth has finite planar coordinates.
     */
    private static boolean finite(EastNorth en) {
        return Double.isFinite(en.east())
                && Double.isFinite(en.north());
    }

    /**
     * Checks whether a face contains the same OSM node more than once.
     */
    private static boolean containsDuplicateNodes(List<Node> nodes) {
        Set<Node> unique = new HashSet<>(nodes);
        return unique.size() != nodes.size();
    }

    /**
     * Finds an existing closed way using exactly the same set of nodes as the
     * generated boundary.
     *
     * <p>Node order is deliberately ignored for duplicate detection.</p>
     */
    private Way findExistingClosedWay(
            DataSet ds,
            List<Node> ringWithClosingNode) {

        Set<Node> ringSet =
                new HashSet<>(ringWithClosingNode);

        for (Way way : ds.getWays()) {
            if (way.isDeleted()
                    || !way.isClosed()) {
                continue;
            }

            if (way.getNodesCount()
                    != ringWithClosingNode.size()) {
                continue;
            }

            Set<Node> waySet =
                    new HashSet<>(way.getNodes());

            if (waySet.equals(ringSet)) {
                return way;
            }
        }

        return null;
    }

    /**
     * Undirected graph containing all usable OSM boundary segments.
     */
    private static final class BoundaryGraph {

        private final Map<Node, List<Node>> adjacency =
                new HashMap<>();

        private final List<Edge> edges =
                new ArrayList<>();

        /**
         * Adds an undirected edge.
         *
         * <p>Duplicate segments are suppressed.</p>
         */
        private void addEdge(Node a, Node b) {
            Edge edge = new Edge(a, b);

            if (edges.contains(edge)) {
                return;
            }

            edges.add(edge);

            adjacency
                    .computeIfAbsent(
                            a,
                            ignored -> new ArrayList<>())
                    .add(b);

            adjacency
                    .computeIfAbsent(
                            b,
                            ignored -> new ArrayList<>())
                    .add(a);
        }
    }

    /**
     * Undirected graph edge.
     */
    private static final class Edge {

        private final Node a;
        private final Node b;

        private Edge(Node a, Node b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int hashCode() {
            /*
             * Order-independent hash.
             */
            return System.identityHashCode(a)
                    ^ System.identityHashCode(b);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Edge)) {
                return false;
            }

            Edge other = (Edge) obj;

            return (a == other.a && b == other.b)
                    || (a == other.b && b == other.a);
        }
    }

    /**
     * Directed half-edge used for planar face traversal.
     */
    private static final class HalfEdge {

        private final Node from;
        private final Node to;

        private HalfEdge reverse;
        private HalfEdge next;

        private HalfEdge(Node from, Node to) {
            this.from = from;
            this.to = to;
        }
    }

    /**
     * Displays a JOSM notification.
     */
    private void notify(String msg, int type) {
        new Notification(msg)
                .setIcon(type)
                .show();
    }
}