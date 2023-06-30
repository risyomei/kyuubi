/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.spark

import java.io.File
import java.util.Locale

import org.apache.spark.launcher.SparkAppHandle

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.ENGINE_SPARK_INPROCESS_LAUNCHER_EXTRA_CLASSPATH
import org.apache.kyuubi.engine.KyuubiApplicationManager
import org.apache.kyuubi.engine.spark.SparkInProcessBuilder.classloader

class SparkInProcessBuilder(
    override val proxyUser: String,
    override val conf: KyuubiConf,
    override val engineRefId: String)
  extends SparkProcessBuilder(proxyUser, conf, engineRefId, None) with Logging {

  private var handle: Option[SparkAppHandle] = None
  private val previousloader = Thread.currentThread().getContextClassLoader

  def startInProcess(): Unit = synchronized {
    Thread.currentThread().setContextClassLoader(classloader)
    val launcherUtil =
      new ReflectionUtil("org.apache.spark.launcher.InProcessLauncher", classloader)
    var instance = launcherUtil.createInstance();
    instance = launcherUtil.callMethod(instance, "setMainClass", mainClass)

    KyuubiApplicationManager.tagApplication(engineRefId, shortName, Some("yarn"), conf)

    conf.getAll.foreach { case (k, v) =>
      instance =
        launcherUtil.callMethod(instance, "addSparkArg", "--conf", s"${convertConfigKey(k)}=$v")
    }

    mainResource.foreach { jar =>
      instance = launcherUtil.callMethod(instance, "setAppResource", jar)
    }
    instance = launcherUtil.callMethod(instance, "addSparkArg", "--proxy-user", proxyUser)
    handle = Some(launcherUtil.callMethod(
      instance,
      "startApplication",
      Array.empty[SparkAppHandle.Listener]).asInstanceOf[SparkAppHandle])

    Thread.currentThread().setContextClassLoader(previousloader)
  }

  override def getError: Throwable = {
    handle.map(_.getError.orElse(new KyuubiException("No Additional Error Message"))).getOrElse(
      new KyuubiException("Unable to obtain error from the handle"))
  }

  def isFailed: Boolean = {
    handle.exists(h =>
      h.getState == SparkAppHandle.State.FAILED ||
        h.getState == SparkAppHandle.State.KILLED ||
        h.getState == SparkAppHandle.State.LOST)
  }
  def isFinished: Boolean = handle.exists(_.getState == SparkAppHandle.State.FINISHED)

  override def close(destroyProcess: Boolean = true): Unit = synchronized {
    Thread.currentThread().setContextClassLoader(previousloader)
    handle.foreach(_.kill())
  }
}

object SparkInProcessBuilder extends Logging {

  lazy val classloader = {
    val defaultConf: KyuubiConf = new KyuubiConf().loadFileDefaults()
    val execJars = defaultConf.get(ENGINE_SPARK_INPROCESS_LAUNCHER_EXTRA_CLASSPATH)
    val jars = execJars.getOrElse("").split(File.pathSeparator).flatMap {
      case path if new File(path).getName == "*" =>
        val files = new File(path).getParentFile.listFiles()
        if (files == null) {
          warn(s"extra classpath '$path' does not exist.")
          Nil
        } else {
          files.filter(_.getName.toLowerCase(Locale.ROOT).endsWith(".jar"))
        }
      case path =>
        new File(path) :: Nil
    }.map(_.toURI.toURL)

    // Assuming the SparkInpProcessBuilder will always be initialized from a same thread.
    // This should be true as it will be initialized only once, anyway
    new IsolatedChildFirstClassloader(jars, Thread.currentThread().getContextClassLoader)
  }
}
