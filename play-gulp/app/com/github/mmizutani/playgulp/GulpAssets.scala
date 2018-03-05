package com.github.mmizutani.playgulp

import java.io.File
import java.net.URLConnection

import akka.stream.scaladsl.StreamConverters
import javax.inject.{Inject, Singleton}
import play.api._
import play.api.http._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GulpAssets @Inject()(
    cc: ControllerComponents,
    env: Environment,
    conf: Configuration,
    assets: _root_.controllers.Assets)(implicit val ec: ExecutionContext)
    extends AbstractController(cc) {

  private lazy val logger = Logger(getClass)

  /**
    * Serve the index page (ui/{src,dist}/index.html) built by Gulp tasks
    *
    * @return Index HTML file
    */
  def index = Action.async { request =>
    if (request.path.endsWith("/")) {
      at("index.html").apply(request)
    } else {
      Future(Redirect(request.path + "/"))
    }
  }

  def redirectRoot(base: String = "/ui/") = Action { request =>
    if (base.endsWith("/")) {
      Redirect(base)
    } else {
      Redirect(base + "/")
    }
  }

  val runtimeDirs = Some(conf.get[Seq[String]]("gulp.devDirs"))

  // List of UI directories from which static assets are served in the development mode
  // in descending order of importance
  val basePaths: List[java.io.File] = runtimeDirs match {
    case Some(dirs) => dirs.toList.map(env.getFile)
    case _ =>
      List(
        env.getFile("ui/.tmp/serve"),
        env.getFile("ui/src"),
        env.getFile("ui")
      ) // If "gulp.devDirs" is not specified in conf/application.conf
  }

  lazy val atHandler: String => Action[AnyContent] =
    env.mode match {
      case Mode.Prod => prodAssetHandler(_: String)
      case _         => devAssetHandler(_: String)
    }

  /**
    * Asset handler for development/test/production modes
    *
    * @param file Path and file name of the static asset served to the client in each mode
    * @return Static asset file
    */
  def at(file: String): Action[AnyContent] = atHandler(file)

  /**
    * Asset Handler for development and test modes
    *
    * @param file Path and file name of the static asset served to the client in the development mode
    * @return Static asset file
    */
  private[playgulp] def devAssetHandler(file: String): Action[AnyContent] =
    Action { request =>
      // Generates a non-strict list of the full paths
      val targetPaths = basePaths.view map {
        new File(_, file)
      }

      //play.mvc.FileMimeTypes      AssetsComponents
      //play.api.http.FileMimeTypes InjectedController < BaseController < BaseControllerHelpers
      // Generates responses returning the file in the dev and test modes only (not in the production mode)
      val responses = targetPaths filter { file =>
        file.exists()
      } map { file =>
        if (file.isFile) {
          logger.info(s"Serving $file")
          val connection: URLConnection = file.toURI.toURL.openConnection
          val source =
            StreamConverters.fromInputStream(() => connection.getInputStream)
          val maybeMimeType: Option[String] = fileMimeTypes
            .forFileName(file.getName)
            .orElse(Some(ContentTypes.BINARY))
          Ok.sendEntity(
              HttpEntity.Streamed(
                source,
                Some(file.length()),
                maybeMimeType
              ))
            .withHeaders(CACHE_CONTROL -> "no-cache")
        } else {
          Forbidden(views.html.defaultpages.unauthorized())
        }
      }

      // Returns the first valid path if valid or NotFound otherwise
      responses.headOption getOrElse NotFound(
        "404 - Page not found error\n" + request.path)
    }

  /**
    * Asset handler for production mode
    *
    * Static asset files (JavaScript, CSS, images, etc.) in app/assets, public and ui/dist folders
    * are all placed in the /public folder (or any other path configured by play.assets.path in application.conf)
    * of the classpath in production mode.
    *
    * @param file Path and file name of the static asset served to the client in the production mode
    * @return Static asset file
    */
  private[playgulp] def prodAssetHandler(file: String): Action[AnyContent] =
    assets.at(file)

}
