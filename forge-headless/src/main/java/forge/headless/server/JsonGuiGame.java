package forge.headless.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.server.DeltaSyncManager;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.headless.protocol.JsonViewCodec;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * Server-side IGuiGame proxy speaking JSON over a WebSocket channel - the
 * JSON twin of {@link RemoteClientGuiGame}
 * (docs/PROTOCOL.md). Interactive calls are forwarded as
 * {"t":"call","m":...,"a":[...]} frames (with an "id" when a reply is
 * expected); the trackable graph is delta-synced to the client before any
 * call whose args reference view objects. Purely visual calls are no-ops.
 *
 * A null reply from the client selects a sensible default (the dialog's
 * default option, the first N choices of a mandatory selection, decline
 * when optional) so a minimal client can play legally by answering null
 * to everything.
 */
public final class JsonGuiGame extends AbstractGuiGame {
    private final Channel channel;
    // serializeNulls: null delta values mean "reverted to default"
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final DeltaSyncManager syncManager = new DeltaSyncManager();
    private final ReplyPool replies = new ReplyPool();
    private final AtomicInteger nextId = new AtomicInteger();
    private final Map<Integer, PendingCall> pending = new ConcurrentHashMap<>();
    private volatile IGameController controller;

    private record PendingCall(ProtocolMethod method, Object[] args) {
    }

    public JsonGuiGame(final Channel channel) {
        this.channel = channel;
    }

    // --- transport helpers ---

    private void send(final ProtocolMethod method, final Object... args) {
        final JsonObject frame = new JsonObject();
        frame.addProperty("t", "call");
        frame.addProperty("m", method.name());
        frame.add("a", encodeArgs(method, args));
        write(frame);
    }

    private void syncAndSend(final ProtocolMethod method, final Object... args) {
        updateGameView();
        send(method, args);
    }

    private <T> T sendAndWait(final ProtocolMethod method, final Object... args) {
        final int id = nextId.incrementAndGet();
        pending.put(id, new PendingCall(method, args));
        replies.initialize(id);
        final JsonObject frame = new JsonObject();
        frame.addProperty("t", "call");
        frame.addProperty("id", id);
        frame.addProperty("m", method.name());
        frame.add("a", encodeArgs(method, args));
        write(frame);
        @SuppressWarnings("unchecked")
        final T result = (T) replies.get(id);
        return result;
    }

    private <T> T syncAndSendAndWait(final ProtocolMethod method, final Object... args) {
        updateGameView();
        return sendAndWait(method, args);
    }

    private void write(final JsonObject frame) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(frame)));
        }
    }

    /** Collects and ships pending state changes as a delta frame. Game-thread-only. */
    public void updateGameView() {
        final GameView gameView = getGameView();
        if (gameView == null) {
            return;
        }
        final DeltaPacket delta = syncManager.collectDeltas(gameView);
        if (!delta.isEmpty()) {
            write(JsonViewCodec.delta(delta));
        }
    }

    private JsonArray encodeArgs(final ProtocolMethod method, final Object[] args) {
        final JsonArray array = new JsonArray();
        for (final Object arg : args) {
            if (arg instanceof FSerializableFunction) {
                array.add(com.google.gson.JsonNull.INSTANCE); // display fns are applied server-side
            } else {
                array.add(JsonViewCodec.encodeArg(arg));
            }
        }
        return array;
    }

    // --- inbound (called from the netty thread by PlaySeatHandler) ---

    /** Completes a blocking call with the client's (decoded) reply. */
    public void handleReply(final int id, final JsonElement value) {
        final PendingCall call = pending.remove(id);
        if (call == null) {
            System.out.println("Reply for unknown call id " + id);
            return;
        }
        replies.complete(id, decodeReply(call, value));
    }

    /** Synthetic left-click for inputs that carry a mouse event on desktop. */
    private static final ITriggerEvent CLICK = new ITriggerEvent() {
        @Override public int getButton() { return 1; }
        @Override public int getX() { return 0; }
        @Override public int getY() { return 0; }
    };

    /** Dispatches a fire-and-forget client input to the game controller. */
    public void handleInput(final JsonObject json) {
        final String method = json.get("m").getAsString();
        final IGameController c = controller;
        if (c == null) {
            System.out.println("Input before controller ready: " + method);
            return;
        }
        switch (method) {
            case "passPriority": c.passPriority(); break;
            case "selectButtonOk": c.selectButtonOk(); break;
            case "selectButtonCancel": c.selectButtonCancel(); break;
            case "concede": c.concede(); break;
            case "alphaStrike": c.alphaStrike(); break;
            case "undoLastAction": c.undoLastAction(); break;
            case "selectCard": {
                final int id = json.getAsJsonObject("a").get("card").getAsInt();
                final CardView card = getGameView().getTracker().getObj(forge.trackable.TrackableTypes.CardViewType, id);
                if (card == null) {
                    System.out.println("selectCard: unknown card id " + id);
                } else {
                    c.selectCard(card, null, CLICK);
                }
                break;
            }
            case "selectPlayer": {
                final int id = json.getAsJsonObject("a").get("player").getAsInt();
                final PlayerView player = getGameView().getTracker().getObj(forge.trackable.TrackableTypes.PlayerViewType, id);
                if (player == null) {
                    System.out.println("selectPlayer: unknown player id " + id);
                } else {
                    c.selectPlayer(player, CLICK);
                }
                break;
            }
            default: System.out.println("Unsupported input: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    private Object decodeReply(final PendingCall call, final JsonElement v) {
        final Object[] a = call.args();
        final boolean isNull = v == null || v.isJsonNull();
        switch (call.method()) {
            case showConfirmDialog:
                return isNull ? a[4] : v.getAsBoolean();
            case confirm:
                return isNull ? a[2] : v.getAsBoolean();
            case showOptionDialog:
                return isNull ? a[4] : v.getAsInt();
            case showInputDialog:
                return isNull ? a[3] : v.getAsString();
            case getAbilityToPlay:
                // null = decline; an integer indexes the offered abilities
                return isNull ? null : ((List<SpellAbilityView>) a[1]).get(v.getAsInt());
            case getChoices: {
                final List<Object> choices = (List<Object>) a[3];
                final int min = (Integer) a[1];
                if (isNull) {
                    return new ArrayList<>(choices.subList(0, Math.min(min, choices.size())));
                }
                return pickByIndex(choices, v);
            }
            case chooseSingleEntityForEffect: {
                final List<GameEntityView> options = (List<GameEntityView>) a[1];
                final boolean isOptional = (Boolean) a[3];
                if (isNull) {
                    return isOptional || options.isEmpty() ? null : options.get(0);
                }
                return options.get(v.getAsInt());
            }
            case chooseEntitiesForEffect: {
                final List<GameEntityView> options = (List<GameEntityView>) a[1];
                final int min = (Integer) a[2];
                if (isNull) {
                    return new ArrayList<>(options.subList(0, Math.min(min, options.size())));
                }
                return pickByIndex(options, v);
            }
            case manipulateCardList: {
                // null = leave the list as offered (identity ordering)
                if (isNull) {
                    final List<CardView> unchanged = new ArrayList<>();
                    for (final CardView card : (Iterable<CardView>) a[1]) {
                        unchanged.add(card);
                    }
                    return unchanged;
                }
                System.out.println("manipulateCardList non-null replies not implemented yet; keeping order");
                final List<CardView> fallback = new ArrayList<>();
                for (final CardView card : (Iterable<CardView>) a[1]) {
                    fallback.add(card);
                }
                return fallback;
            }
            default:
                if (!isNull) {
                    System.out.println("No decoder for " + call.method() + " reply; using null");
                }
                return null;
        }
    }

    private static <T> List<T> pickByIndex(final List<T> options, final JsonElement indices) {
        final List<T> picked = new ArrayList<>();
        for (final JsonElement e : indices.getAsJsonArray()) {
            picked.add(options.get(e.getAsInt()));
        }
        return picked;
    }

    // --- lifecycle / wiring ---

    @Override
    public void setOriginalGameController(final PlayerView player, final IGameController gameController) {
        super.setOriginalGameController(player, gameController);
        this.controller = gameController;
    }

    @Override
    public void openView(final forge.trackable.TrackableCollection<PlayerView> myPlayers) {
        send(ProtocolMethod.openView, myPlayers);
        updateGameView();
    }

    @Override
    public void afterGameEnd() {
        syncAndSend(ProtocolMethod.afterGameEnd);
        super.afterGameEnd();
    }

    @Override
    public void finishGame() {
        syncAndSend(ProtocolMethod.finishGame);
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    @Override
    public boolean isNetGame() {
        return true;
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final forge.game.phase.PhaseType phase) {
        return false;
    }

    @Override
    public void setPlayerAvatar(final forge.LobbyPlayer player, final forge.game.player.IHasIcon ihi) {
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
        syncAndSend(ProtocolMethod.setPanelSelection, hostCard);
    }

    @Override
    public forge.game.GameState getGamestate() {
        return null;
    }

    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        updateGameView();
        send(ProtocolMethod.setWeaklySelectable, cards);
    }

    @Override
    public void clearWeaklySelectable() {
        send(ProtocolMethod.clearWeaklySelectable);
    }

    @Override
    public void showWaitingTimer(final PlayerView forPlayer, final String waitingForPlayerName) {
        send(ProtocolMethod.showWaitingTimer, forPlayer, waitingForPlayerName);
    }

    @Override
    public void updateDrawOffer(final forge.gamemodes.match.DrawOfferMessage.Status update) {
        send(ProtocolMethod.updateDrawOffer, update);
    }

    @Override
    public void applyYieldUpdate(final forge.gamemodes.match.YieldUpdate update) {
        send(ProtocolMethod.applyYieldUpdate, update);
    }

    @Override
    public void updateShards(final Iterable<PlayerView> shardsUpdate) {
    }

    @Override
    public void showManaPool(final PlayerView player) {
        send(ProtocolMethod.showManaPool, player);
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        send(ProtocolMethod.hideManaPool, player);
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public void enableOverlay() {
        send(ProtocolMethod.enableOverlay);
    }

    @Override
    public void disableOverlay() {
        send(ProtocolMethod.disableOverlay);
    }

    // --- interactive surface (mirrors RemoteClientGuiGame) ---

    @Override
    public void showCombat() {
        syncAndSend(ProtocolMethod.showCombat);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        if (card == null) {
            send(ProtocolMethod.showPromptMessage, playerView, message, null);
        } else {
            syncAndSend(ProtocolMethod.showPromptMessage, playerView, message, card);
        }
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2,
            final boolean enable1, final boolean enable2, final boolean focus1) {
        send(ProtocolMethod.updateButtons, owner, label1, label2, enable1, enable2, focus1);
    }

    @Override
    public void flashIncorrectAction() {
        send(ProtocolMethod.flashIncorrectAction);
    }

    @Override
    public void alertUser() {
        send(ProtocolMethod.alertUser);
    }

    @Override
    public void message(final String message, final String title) {
        send(ProtocolMethod.message, message, title);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        send(ProtocolMethod.showErrorDialog, message, title);
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
            final String noButtonText, final boolean defaultYes) {
        final Boolean result = sendAndWait(ProtocolMethod.showConfirmDialog, message, title, yesButtonText, noButtonText, defaultYes);
        return result != null ? result : defaultYes;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
            final List<String> options, final int defaultOption) {
        final Integer result = syncAndSendAndWait(ProtocolMethod.showOptionDialog, message, title, icon, options, defaultOption);
        return result != null ? result : defaultOption;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
            final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return syncAndSendAndWait(ProtocolMethod.showInputDialog, message, title, icon, initialInput, inputOptions, isNumeric);
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final Boolean result = syncAndSendAndWait(ProtocolMethod.confirm, c, question, defaultIsYes, options);
        return result != null ? result : defaultIsYes;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
            final List<T> selected, final FSerializableFunction<T, String> display) {
        return syncAndSendAndWait(ProtocolMethod.getChoices, message, min, max, choices, selected, display);
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
            final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
        // No decoder yet: a null reply keeps the offered order (sourceChoices appended to destChoices)
        final OrderResult<T> result = syncAndSendAndWait(ProtocolMethod.order, title, top, remainingObjectsMin,
                remainingObjectsMax, sourceChoices, destChoices, referenceCard, sideboardingMode, showRememberCheckbox);
        if (result != null) {
            return result;
        }
        final List<T> ordered = new ArrayList<>(destChoices != null ? destChoices : List.of());
        if (sourceChoices != null) {
            ordered.addAll(sourceChoices);
        }
        return new OrderResult<>(ordered, false);
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return syncAndSendAndWait(ProtocolMethod.sideboard, sideboard, main, message);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
            final DelayedReveal delayedReveal, final boolean isOptional) {
        return syncAndSendAndWait(ProtocolMethod.chooseSingleEntityForEffect, title, optionList, delayedReveal, isOptional);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
            final int min, final int max, final DelayedReveal delayedReveal) {
        return syncAndSendAndWait(ProtocolMethod.chooseEntitiesForEffect, title, optionList, min, max, delayedReveal);
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
            final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        return syncAndSendAndWait(ProtocolMethod.manipulateCardList, title, cards, manipulable, toTop, toBottom, toAnywhere);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
            final ITriggerEvent triggerEvent) {
        return syncAndSendAndWait(ProtocolMethod.getAbilityToPlay, hostCard, abilities, null);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers,
            final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        return syncAndSendAndWait(ProtocolMethod.assignCombatDamage, attacker, blockers, damage, defender, overrideOrder, maySkip);
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> targets,
            final int amount, final boolean atLeastOne, final String amountLabel) {
        return syncAndSendAndWait(ProtocolMethod.assignGenericAmount, effectSource, targets, amount, atLeastOne, amountLabel);
    }

    @Override
    public void setCard(final CardView card) {
        syncAndSend(ProtocolMethod.setCard, card);
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        syncAndSend(ProtocolMethod.setSelectables, cards, min, max);
    }

    @Override
    public void clearSelectables() {
        send(ProtocolMethod.clearSelectables);
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        return syncAndSendAndWait(ProtocolMethod.tempShowZones, controller, zonesToUpdate);
    }

    @Override
    public void hideZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        syncAndSend(ProtocolMethod.hideZones, controller, zonesToUpdate);
    }

    @Override
    public PlayerZoneUpdates openZones(final PlayerView controller, final Collection<ZoneType> zones,
            final Map<PlayerView, Object> players, final boolean backupLastZones) {
        final PlayerZoneUpdates result = syncAndSendAndWait(ProtocolMethod.openZones, controller, zones, players, backupLastZones);
        return result != null ? result : new PlayerZoneUpdates();
    }

    @Override
    public void restoreOldZones(final PlayerView playerView, final PlayerZoneUpdates playerZoneUpdates) {
        syncAndSend(ProtocolMethod.restoreOldZones, playerView, playerZoneUpdates);
    }
}
