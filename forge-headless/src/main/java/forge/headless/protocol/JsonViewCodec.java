package forge.headless.protocol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
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
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;

/**
 * Encodes the engine's TrackableObject view tree as JSON per docs/PROTOCOL.md.
 *
 * Layout: a flat object table keyed by "type:id" (card states use
 * "cardState:id:stateOrdinal", mirroring DeltaPacket's composite keys), with
 * cross-references encoded as {"$ref": "key"}. Property names are the
 * TrackableProperty enum names verbatim.
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

    /** Walks the full view tree reachable from the given GameView. */
    public static JsonObject snapshot(final GameView game) {
        final JsonObject objects = new JsonObject();
        final Deque<TrackableObject> queue = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        final String rootKey = enqueue(game, queue, seen);
        while (!queue.isEmpty()) {
            final TrackableObject obj = queue.poll();
            objects.add(keyOf(obj), encodeObject(obj, queue, seen));
        }
        final JsonObject root = new JsonObject();
        root.addProperty("root", rootKey);
        root.add("objects", objects);
        return root;
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

    private static JsonObject encodeObject(final TrackableObject obj, final Deque<TrackableObject> queue, final Set<String> seen) {
        final JsonObject json = new JsonObject();
        final Map<TrackableProperty, Object> props = obj.getProps();
        for (final Map.Entry<TrackableProperty, Object> e : props.entrySet()) {
            json.add(e.getKey().name(), encodeValue(e.getValue(), queue, seen));
        }
        return json;
    }

    private static JsonElement encodeValue(final Object value, final Deque<TrackableObject> queue, final Set<String> seen) {
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
            ref.addProperty("$ref", enqueue((TrackableObject) value, queue, seen));
            return ref;
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
                map.add(String.valueOf(e.getKey()), encodeValue(e.getValue(), queue, seen));
            }
            return map;
        }
        if (value instanceof Iterable) {
            final JsonArray array = new JsonArray();
            for (final Object element : (Iterable<?>) value) {
                array.add(encodeValue(element, queue, seen));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
