package com.oceanpark;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

public class MenuScreen extends ScreenAdapter {
    private static final float WORLD_WIDTH = 1280f;
    private static final float WORLD_HEIGHT = 720f;

    private static final Color BG = Color.valueOf("061007");
    private static final Color PRIMARY = Color.valueOf("35FF74");
    private static final Color DIM = Color.valueOf("21964A");
    private static final Color PANEL = Color.valueOf("0E1E12");
    private static final Color PANEL_DARK = Color.valueOf("07140A");

    private final GameApp game;
    private final Viewport viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
    private final GlyphLayout layout = new GlyphLayout();
    private final Vector3 pointer = new Vector3();

    private final Rectangle nicknameBox = new Rectangle(340, 440, 600, 64);
    private final Rectangle playButton = new Rectangle(490, 340, 300, 74);

    private final StringBuilder nickname = new StringBuilder("Player");
    private boolean editingNickname = true;

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean keyTyped(char character) {
            if (!editingNickname) {
                return false;
            }

            if (character == '\b') {
                if (nickname.length() > 0) {
                    nickname.deleteCharAt(nickname.length() - 1);
                }
                return true;
            }

            if (character == '\r' || character == '\n') {
                startGame();
                return true;
            }

            if (nickname.length() < 16
                && (Character.isLetterOrDigit(character) || character == '_' || character == '-')) {
                nickname.append(character);
                return true;
            }

            return false;
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.ENTER || keycode == Input.Keys.SPACE) {
                startGame();
                return true;
            }
            return false;
        }

        @Override
        public boolean touchDown(int screenX, int screenY, int pointerId, int button) {
            viewport.unproject(pointer.set(screenX, screenY, 0));
            editingNickname = nicknameBox.contains(pointer.x, pointer.y);
            if (playButton.contains(pointer.x, pointer.y)) {
                startGame();
                return true;
            }
            return true;
        }
    };

    public MenuScreen(GameApp game) {
        this.game = game;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
        Gdx.input.setOnscreenKeyboardVisible(true);
    }

    @Override
    public void hide() {
        Gdx.input.setOnscreenKeyboardVisible(false);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(BG.r, BG.g, BG.b, BG.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();

        ShapeRenderer shapes = game.getShapeRenderer();
        shapes.setProjectionMatrix(viewport.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(PANEL_DARK);
        shapes.rect(280, 210, 720, 360);
        shapes.setColor(editingNickname ? PANEL : PANEL_DARK);
        shapes.rect(nicknameBox.x, nicknameBox.y, nicknameBox.width, nicknameBox.height);
        shapes.setColor(PRIMARY);
        shapes.rect(playButton.x, playButton.y, playButton.width, playButton.height);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(PRIMARY);
        shapes.rect(280, 210, 720, 360);
        shapes.rect(nicknameBox.x, nicknameBox.y, nicknameBox.width, nicknameBox.height);
        shapes.setColor(DIM);
        shapes.rect(playButton.x, playButton.y, playButton.width, playButton.height);
        shapes.end();

        SpriteBatch batch = game.getBatch();
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();

        BitmapFont font = game.getFont();
        drawCentered(batch, font, "OCEAN PARK", 610, 3.2f, PRIMARY);
        drawCentered(batch, font, "Nickname", 525, 1.4f, DIM);
        drawCentered(batch, font, nickname.toString() + (editingNickname ? "_" : ""), 485, 1.8f, PRIMARY);
        drawCentered(batch, font, "PLAY", 388, 2.0f, Color.BLACK);
        drawCentered(batch, font, GameSession.get().getStatus(), 270, 1.0f, DIM);
        drawCentered(batch, font, "ENTER/SPACE: PLAY", 235, 1.0f, DIM);

        batch.end();

        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
    }

    private void drawCentered(SpriteBatch batch, BitmapFont font, String text, float y, float scale, Color color) {
        font.getData().setScale(scale);
        font.setColor(color);
        layout.setText(font, text);
        font.draw(batch, text, (WORLD_WIDTH - layout.width) * 0.5f, y);
    }

    private void startGame() {
        String nick = GameSession.sanitizeNickname(nickname.toString());
        GameSession.get().setRequestedNickname(nick);
        game.setScreen(new LoadingScreen(game, 0));
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }
}
