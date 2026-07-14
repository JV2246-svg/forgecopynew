package forge.headless.protocol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;

/**
 * Encodes the engine's TrackableObject view tree as JSON per docs/PROTOCOL.md.
 *
 * Snapshot layout: a flat object table keyed by "type:id" (card states use
 * "cardState:id:stateOrdinal", mirroring DeltaPacket's composite keys), with
 * cross-references encoded as {"$ref": "key"}. Property names are the
 * TrackableProperty enum names verbatim.
 *
 * Delta layout: the same keys and property names over DeltaPacket's already
 * network-normalized values (bare ids for object refs, id arrays for
 * collections, CombatData for combat).
 *
 * IMPORTANT: writers must serialize with Gson's serializeNulls() enabled.
 * A null property value in a delta means "reverted to default"; default Gson
 * silently drops null members, losing the reset.
 *
 * Values are dispatched on runtime type rather than declared TrackableType:
 * primitives, enums (name), TrackableObject (ref), Multiset (name->count),
 * Map (stringified key -> encoded value), Iterable (array). Anything else
 * falls back to String.valueOf - which covers ManaCost, ColorSet and friends
 * with their human-readable forms.
 */
public final class JsonViewCodec {
    private JsonViewCodec() {
    }

    /** Resolves a TrackableObject encountered in a value to its table key. */
    private interface RefEncoder {
        String keyFor(TrackableObject obj);
    }

    /** Walks the full view tree reachable from the given GameView. */
    public static JsonObject snapshot(final GameView game) {
        final JsonObject objects = new JsonObject();
        final Deque<TrackableObject> queue = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        final RefEncoder enqueuing = obj -> enqueue(obj, queue, seen);
        final String rootKey = enqueue(game, queue, seen);
        while (!queue.isEmpty()) {
            final TrackableObject obj = queue.poll();
            objects.add(keyOf(obj), encodeProps(obj.getProps(), enqueuing));
        }
        final JsonObject root = new JsonObject();
        root.addProperty("root", rootKey);
        root.add("objects", objects);
        return root;
    }

    /** Encodes a collected DeltaPacket. Keys and property names match snapshots. */
    public static JsonObject delta(final DeltaPacket packet) {
        final JsonObject root = new JsonObject();
        root.addProperty("t", "delta");
        root.addProperty("seq", packet.getSequenceNumber());
        root.add("changed", encodeDeltaTable(packet.getObjectDeltas()));
        root.add("new", encodeDeltaTable(packet.getNewObjects()));
        if (packet.hasChecksum()) {
            root.addProperty("checksum", packet.getChecksum());
            final JsonArray props = new JsonArray();
            for (final int ordinal : packet.getChecksumProperties()) {
                props.add(ordinal);
            }
            root.add("checksumProps", props);
        }
        return root;
    }

    private static JsonObject encodeDeltaTable(final Map<Integer, Map<TrackableProperty, Object>> table) {
        final JsonObject json = new JsonObject();
        for (final Map.Entry<Integer, Map<TrackableProperty, Object>> e : table.entrySet()) {
            json.add(deltaKeyToString(e.getKey()), encodeProps(e.getValue(), JsonViewCodec::keyOf));
        }
        return json;
    }

    static String deltaKeyToString(final int deltaKey) {
        final int type = DeltaPacket.getTypeFromDeltaKey(deltaKey);
        final int id = DeltaPacket.getIdFromDeltaKey(deltaKey);
        switch (type) {
            case DeltaPacket.TYPE_CARD_VIEW: return "card:" + id;
            case DeltaPacket.TYPE_PLAYER_VIEW: return "player:" + id;
            case DeltaPacket.TYPE_STACK_ITEM_VIEW: return "stack:" + id;
            case DeltaPacket.TYPE_GAME_VIEW: return "game:" + id;
            case DeltaPacket.TYPE_CSV:
                // CSV ids encode parentId * 16 + stateOrdinal (see DeltaPacket.makeDeltaKey)
                return "cardState:" + Math.floorDiv(id, 16) + ":" + Math.floorMod(id, 16);
            default: return "unknown" + type + ":" + id;
        }
    }

    /** Encodes a protocol-method argument (TrackableObjects become plain refs). */
    public static JsonElement encodeArg(final Object value) {
        return encodeValue(value, JsonViewCodec::keyOf);
    }

    static String keyOf(final TrackableObject obj) {
        if (obj instanceof CardStateView) {
            final CardStateView csv = (CardStateView) obj;
            return "cardState:" + csv.getId() + ":" + csv.getState().ordinal();
        }
        if (obj instanceof CardView) {
            return "card:" + obj.getId();
        }
        if (obj instanceof PlayerView) {
            return "player:" + obj.getId();
        }
        if (obj instanceof StackItemView) {
            return "stack:" + obj.getId();
        }
        if (obj instanceof CombatView) {
            return "combat:" + obj.getId();
        }
        if (obj instanceof GameView) {
            return "game:" + obj.getId();
        }
        return obj.getClass().getSimpleName() + ":" + obj.getId();
    }

    private static String enqueue(final TrackableObject obj, final Deque<TrackableObject> queue, final Set<String> seen) {
        final String key = keyOf(obj);
        if (seen.add(key)) {
            queue.add(obj);
        }
        return key;
    }

    private static JsonObject encodeProps(final Map<TrackableProperty, Object> props, final RefEncoder refs) {
        final JsonObject json = new JsonObject();
        for (final Map.Entry<TrackableProperty, Object> e : props.entrySet()) {
            json.add(e.getKey().name(), encodeValue(e.getValue(), refs));
        }
        return json;
    }

    private static JsonElement encodeValue(final Object value, final RefEncoder refs) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Enum) {
            return new JsonPrimitive(((Enum<?>) value).name());
        }
        if (value instanceof TrackableObject) {
            final JsonObject ref = new JsonObject();
            ref.addProperty("$ref", refs.keyFor((TrackableObject) value));
            return ref;
        }
        if (value instanceof DeltaPacket.CombatData) {
            return encodeCombat((DeltaPacket.CombatData) value);
        }
        if (value instanceof int[]) {
            final JsonArray array = new JsonArray();
            for (final int i : (int[]) value) {
                array.add(i);
            }
            return array;
        }
        if (value instanceof Multiset) {
            final JsonObject counts = new JsonObject();
            for (final Multiset.Entry<?> e : ((Multiset<?>) value).entrySet()) {
                counts.addProperty(String.valueOf(e.getElement()), e.getCount());
            }
            return counts;
        }
        if (value instanceof Map) {
            final JsonObject map = new JsonObject();
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                map.add(String.valueOf(e.getKey()), encodeValue(e.getValue(), refs));
            }
            return map;
        }
        if (value instanceof Iterable) {
            final JsonArray array = new JsonArray();
            for (final Object element : (Iterable<?>) value) {
                array.add(encodeValue(element, refs));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static JsonElement encodeCombat(final DeltaPacket.CombatData combat) {
        final JsonArray bands = new JsonArray();
        for (int i = 0; i < combat.bandAttackerIds.size(); i++) {
            final JsonObject band = new JsonObject();
            band.add("attackers", intList(combat.bandAttackerIds.get(i)));
            final int[] def = combat.bandDefenderRefs.get(i);
            final JsonObject defender = new JsonObject();
            defender.addProperty("kind", def[0] == 0 ? "card" : "player");
            defender.addProperty("id", def[1]);
            band.add("defender", defender);
            final List<Integer> blockers = combat.bandBlockerIds.get(i);
            if (blockers != null) {
                band.add("blockers", intList(blockers));
            }
            final List<Integer> planned = combat.bandPlannedBlockerIds.get(i);
            if (planned != null) {
                band.add("planned", intList(planned));
            }
            bands.add(band);
        }
        final JsonObject json = new JsonObject();
        json.add("bands", bands);
        return json;
    }

    private static JsonArray intList(final List<Integer> values) {
        final JsonArray array = new JsonArray();
        for (final Integer v : values) {
            array.add(v);
        }
        return array;
    }
}
