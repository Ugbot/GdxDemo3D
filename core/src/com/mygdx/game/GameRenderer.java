package com.mygdx.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ai.pfa.Connection;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.BaseLight;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Bits;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.mygdx.game.objects.Billboard;
import com.mygdx.game.objects.GameCharacter;
import com.mygdx.game.objects.GameModel;
import com.mygdx.game.pathfinding.Edge;
import com.mygdx.game.pathfinding.PathPoint;
import com.mygdx.game.pathfinding.Triangle;
import com.mygdx.game.settings.DebugViewSettings;
import com.mygdx.game.settings.GameSettings;
import com.mygdx.game.shaders.UberShader;
import com.mygdx.game.utilities.MyShapeRenderer;
import com.mygdx.game.utilities.Observer;

import java.util.Iterator;


/**
 * Created by user on 7/31/15.
 */
public class GameRenderer implements Disposable, Observer {

	public static final String tag = "GameRenderer";

	private final ModelBatch modelBatch;
	private final ModelBatch shadowBatch;
	private final MyShapeRenderer shapeRenderer;
	private final SpriteBatch spriteBatch;
	private final Vector3 tmp = new Vector3();
	private final Vector3 debugNodePos1 = new Vector3();
	private final Vector3 debugNodePos2 = new Vector3();
	private final Matrix4 tmpMatrix = new Matrix4();
	private final Quaternion tmpQuat = new Quaternion();
	private final Viewport viewport;
	GameCharacter selectedCharacter;
	private BitmapFont font;
	private Camera camera;
	private Environment environment;
	private DirectionalShadowLight shadowLight;
	private GameEngine engine;
	private Bits visibleLayers;
	private Billboard markerBillboard;

	public GameRenderer(Viewport viewport, Camera camera, GameEngine engine) {
		this.viewport = viewport;
		this.camera = camera;
		this.engine = engine;
		visibleLayers = new Bits();
		for (int i = 0; i < 10; i++) {
			visibleLayers.set(i);
		}

		shapeRenderer = new MyShapeRenderer();
		shapeRenderer.setAutoShapeType(true);

		spriteBatch = new SpriteBatch();
		font = new BitmapFont();
		font.setColor(Color.WHITE);
		font.setUseIntegerPositions(false);
		font.getData().setScale(0.01f);
		shadowBatch = new ModelBatch(new DepthShaderProvider());

		ShaderProgram.pedantic = false;
		final String vertUber = Gdx.files.internal("shaders/uber.vert").readString();
		final String fragUber = Gdx.files.internal("shaders/uber.frag").readString();
		modelBatch = new ModelBatch(new DefaultShaderProvider(vertUber, fragUber) {
			@Override
			protected Shader createShader(final Renderable renderable) {
				return new UberShader(renderable, config);
			}
		});


	}

	@Override
	public void notifyEntitySelected(GameCharacter entity) {
		selectedCharacter = entity;
		markerBillboard.setFollowTransform(entity.modelInstance.transform);
	}

	@Override
	public void notifyLayerChanged(Bits layer) {
		this.visibleLayers = layer;
	}

	@Override
	public void dispose() {
		modelBatch.dispose();
		shadowBatch.dispose();
		shapeRenderer.dispose();
		spriteBatch.dispose();
		font.dispose();
		shadowLight.dispose();
	}

	public void setEnvironmentLights(Array<BaseLight> lights, Vector3 sunDirection) {
		environment = new Environment();
		environment.add((shadowLight = new DirectionalShadowLight(
				GameSettings.SHADOW_MAP_WIDTH,
				GameSettings.SHADOW_MAP_HEIGHT,
				GameSettings.SHADOW_VIEWPORT_WIDTH,
				GameSettings.SHADOW_VIEWPORT_HEIGHT,
				GameSettings.SHADOW_NEAR,
				GameSettings.SHADOW_FAR))
				.set(GameSettings.SHADOW_INTENSITY,
						GameSettings.SHADOW_INTENSITY,
						GameSettings.SHADOW_INTENSITY,
						sunDirection.nor()));
		environment.shadowMap = shadowLight;

		float ambientLight = GameSettings.SCENE_AMBIENT_LIGHT;
		environment.set(new ColorAttribute(ColorAttribute.AmbientLight, ambientLight, ambientLight, ambientLight, 1));
		for (BaseLight light : lights) {
			environment.add(light);
		}
	}


	private boolean isVisible(final Camera camera, final GameModel gameModel) {
		if (!gameModel.layers.intersects(visibleLayers)) {
			return false;
		}
		gameModel.modelInstance.transform.getTranslation(tmp);
		tmp.add(gameModel.center);
		return camera.frustum.sphereInFrustum(tmp, gameModel.radius);
	}

	public void update(float deltaTime) {
		if (DebugViewSettings.drawModels) {
			drawShadowBatch();
			camera.update();
			modelBatch.begin(camera);
			Iterator<GameModel> models = engine.getModels();
			while (models.hasNext()) {
				GameModel mdl = models.next();
				if (isVisible(camera, mdl) || mdl.ignoreCulling) {
					modelBatch.render(mdl.modelInstance, environment);
				}
			}
			modelBatch.render(markerBillboard.modelInstance, environment);
			modelBatch.end();
		}
		if (DebugViewSettings.drawArmature) {
			drawArmature();
		}
		if (DebugViewSettings.drawPath) {
			drawPath();
		}
		if (DebugViewSettings.drawNavmesh) {
			drawNavMesh();
		}
	}

	private void drawPath() {
		if (selectedCharacter != null && selectedCharacter.pathData.path.size > 1) {
			shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);
			shapeRenderer.begin(MyShapeRenderer.ShapeType.Line);
			shapeRenderer.setColor(Color.CORAL);
			// Smoothed path
			Vector3 q;
			Vector3 p = selectedCharacter.pathData.currentGoal.point;
			for (int i = selectedCharacter.pathData.path.size - 1; i >= 0; i--) {
				q = selectedCharacter.pathData.path.get(i).point;
				shapeRenderer.line(p, q);
				p = q;
			}
			shapeRenderer.end();
		}
	}

	private void drawNavMesh() {
		Gdx.gl.glEnable(GL20.GL_BLEND);
		Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
		shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);
		shapeRenderer.begin(MyShapeRenderer.ShapeType.Line);
		for (int i = 0; i < engine.navmesh.graph.getNodeCount(); i++) {
			Triangle t = engine.navmesh.graph.getTriangleFromGraphIndex(i);
			shapeRenderer.setColor(Color.LIGHT_GRAY);
			shapeRenderer.line(t.a, t.b);
			shapeRenderer.line(t.b, t.c);
			shapeRenderer.line(t.c, t.a);
		}

		if (selectedCharacter != null) {
			if (selectedCharacter.pathData.trianglePath.getCount() > 0) {
				// Path triangles
				shapeRenderer.set(MyShapeRenderer.ShapeType.Filled);

				for (int i = 0; i < selectedCharacter.pathData.trianglePath.getCount(); i++) {
					Edge e = (Edge) selectedCharacter.pathData.trianglePath.get(i);
					if (selectedCharacter.pathData.currentTriangleIndex == e.fromNode.getIndex()) {
						shapeRenderer.setColor(1, 0, 0, 0.2f);
					} else {
						shapeRenderer.setColor(1, 1, 0, 0.2f);
					}
					shapeRenderer.triangle(e.fromNode.a, e.fromNode.b, e.fromNode.c);
					if (i == selectedCharacter.pathData.trianglePath.getCount() - 1) {
						if (selectedCharacter.pathData.currentTriangleIndex == e.toNode.getIndex()) {
							shapeRenderer.setColor(1, 0, 0, 0.2f);
						} else {
							shapeRenderer.setColor(1, 1, 0, 0.2f);
						}
						shapeRenderer.triangle(e.toNode.a, e.toNode.b, e.toNode.c);
					}
				}
				// Shared triangle edges
				shapeRenderer.set(MyShapeRenderer.ShapeType.Line);
				for (Connection<Triangle> connection : selectedCharacter.pathData.trianglePath) {
					Edge e = (Edge) connection;
					shapeRenderer.line(e.rightVertex, e.leftVertex, Color.GREEN, Color.RED);
				}
			} else if (selectedCharacter.pathData.currentTriangleIndex != -1) {
				shapeRenderer.set(MyShapeRenderer.ShapeType.Filled);
				Triangle tri = engine.navmesh.graph.getTriangleFromGraphIndex(selectedCharacter.pathData.currentTriangleIndex);
				shapeRenderer.setColor(1, 0, 0, 0.2f);
				shapeRenderer.triangle(tri.a, tri.b, tri.c);
			}
			if (selectedCharacter.pathData.trianglePath.debugPathPoints.size > 0) {
				Array<PathPoint> pathPoints = selectedCharacter.pathData.trianglePath.debugPathPoints;
				shapeRenderer.set(MyShapeRenderer.ShapeType.Line);

				// Smoothed path
				Vector3 q;
				Vector3 p = pathPoints.get(pathPoints.size - 1).point;
				float r = 0.02f;
				float s = r / 2;
				shapeRenderer.setColor(Color.WHITE);
				for (int i = pathPoints.size - 1; i >= 0; i--) {
					q = pathPoints.get(i).point;
					shapeRenderer.setColor(Color.CYAN);
					shapeRenderer.line(p, q);
					p = q;
					shapeRenderer.setColor(Color.WHITE);
					shapeRenderer.box(p.x - s, p.y - s, p.z + s, r, r, r);
				}
			}
		}
		shapeRenderer.end();
		// Draw indices of the triangles
		spriteBatch.begin();
		spriteBatch.setProjectionMatrix(camera.combined);
		for (int i = 0; i < engine.navmesh.graph.getNodeCount(); i++) {
			Triangle t = engine.navmesh.graph.getTriangleFromGraphIndex(i);
			tmpMatrix.set(camera.view).inv().getRotation(tmpQuat);
			tmpMatrix.setToTranslation(t.centroid).rotate(tmpQuat);
			spriteBatch.setTransformMatrix(tmpMatrix);
			font.draw(spriteBatch, Integer.toString(t.triIndex), 0, 0);
		}
		spriteBatch.end();


		Gdx.gl.glDisable(GL20.GL_BLEND);
	}

	private void drawShadowBatch() {
		int vw = viewport.getScreenWidth();
		int vh = viewport.getScreenHeight();
		int vx = viewport.getScreenX();
		int vy = viewport.getScreenY();

		shadowLight.begin(Vector3.Zero, camera.direction);
		shadowBatch.begin(shadowLight.getCamera());
		Iterator<GameModel> models = engine.getModels();
		while (models.hasNext()) {
			GameModel mdl = models.next();
			if (!mdl.layers.intersects(visibleLayers)) {
				continue;
			}
			shadowBatch.render(mdl.modelInstance);
		}
		shadowBatch.end();
		shadowLight.end();

		viewport.update(vw, vh);
		viewport.setScreenX(vx);
		viewport.setScreenY(vy);
		viewport.apply();
	}

	private void drawArmature() {
		shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);
		shapeRenderer.begin(MyShapeRenderer.ShapeType.Line);
		shapeRenderer.setColor(0, 1, 1, 1);
		if (selectedCharacter != null) {
			Node skeleton = selectedCharacter.modelInstance.getNode("armature");
			if (skeleton != null) {
				selectedCharacter.modelInstance.transform.getTranslation(tmp);
				skeleton.globalTransform.getTranslation(debugNodePos1);
				drawArmatureNodes(skeleton, tmp, debugNodePos1, debugNodePos2);
			}
		}
		shapeRenderer.end();
	}

	private void drawArmatureNodes(Node currentNode, Vector3 modelPos,
								   Vector3 parentNodePos, Vector3 currentNodePos) {
		currentNode.globalTransform.getTranslation(currentNodePos);
		currentNodePos.add(modelPos);
		shapeRenderer.setColor(Color.GREEN);
		shapeRenderer.box(currentNodePos.x, currentNodePos.y, currentNodePos.z, 0.01f, 0.01f, 0.01f);
		shapeRenderer.setColor(Color.YELLOW);
		if (currentNode.hasParent()) {
			shapeRenderer.line(parentNodePos, currentNodePos);
		}
		if (currentNode.hasChildren()) {
			float x = currentNodePos.x;
			float y = currentNodePos.y;
			float z = currentNodePos.z;
			for (Node child : currentNode.getChildren()) {
				drawArmatureNodes(child, modelPos, currentNodePos, parentNodePos);
				currentNodePos.set(x, y, z);
			}
		}
	}

	public void setSelectionMarker(Billboard markerBillboard) {
		this.markerBillboard = markerBillboard;
	}
}
