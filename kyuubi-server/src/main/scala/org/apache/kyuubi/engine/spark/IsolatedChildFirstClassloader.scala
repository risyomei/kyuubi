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

import java.io.IOException
import java.net.URL
import java.util

import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.ENGINE_SPARK_INPROCESS_LAUNCHER_SHARED_CLASS_PREFIX
import org.apache.kyuubi.engine.spark.IsolatedChildFirstClassloader.extraSharedClasses

class IsolatedChildFirstClassloader(
    urls: Seq[URL],
    parent: ClassLoader) extends URLClassLoader(urls.toArray.toSeq, parent)
  with Logging {

  protected def classToPath(name: String): String =
    name.replaceAll("\\.", "/") + ".class"

  protected def isSharedClass(name: String): Boolean = {
    val isHadoopClass =
      name.startsWith("org.apache.hadoop.") && !name.startsWith("org.apache.hadoop.hive.") ||
        name.startsWith("org.apache.commons.configuration2.")

    val isExtraSharedClass = extraSharedClasses.exists(_.startsWith(name))

    /**
     * org.apache.hadoop.hive.common.io.SessionStream class is only in cdpd-hive,
     * and only ever referenced in unit tests currently.
     * The class should be shared because it is set outside
     * "in the outer class loader (to setup the unit test) and within the isolated hive code.
     */

    /**
     *  org.apache.spark.launcher.SparkAppHandle has to be loaded by the classloader of main thread
     */

    name.startsWith("org.slf4j") ||
    name.startsWith("org.apache.hadoop.hive.common.io.SessionStream") ||
    name.startsWith("org.apache.log4j") || // log4j1.x
    name.startsWith("org.apache.logging.log4j") || // log4j2
    name.startsWith("org.apache.kyuubi") ||
    name.startsWith("org.apache.spark.launcher.SparkAppHandle") ||
    isHadoopClass ||
    isExtraSharedClass ||
    name.startsWith("scala.") ||
    (name.startsWith("com.google") && !name.startsWith("com.google.cloud")) ||
    name.startsWith("java.") ||
    name.startsWith("javax.sql.")
  }
  override def loadClass(name: String, resolve: Boolean): Class[_] =
    getClassLoadingLock(name).synchronized {
      debug(s"====Trying to load class: $name)}")
      var loaded = findLoadedClass(name)

      if (loaded == null) {
        try {
          if (isSharedClass(name)) {
            loaded = parent.loadClass(name)
            debug(s"loaded shared class: $name - ${getResource(classToPath(name))}")
          } else {
            loaded = findClass(name)
            debug(s"loaded isolated class: $name - ${getResource(classToPath(name))}")
          }
        } catch {
          case _: ClassNotFoundException =>
            loaded = super.loadClass(name, resolve)
        }
      }
      if (resolve) {
        resolveClass(loaded)
      }
      loaded
    }

  @throws[IOException]
  override def getResources(name: String): util.Enumeration[URL] = {
    val allRes = new util.LinkedList[URL]
    // load resource from this classloader

    val thisRes = if (isSharedClass(name)) parent.getResources(name) else findResources(name)

    if (thisRes != null) {
      while (thisRes.hasMoreElements) {
        allRes.add(thisRes.nextElement)
      }
    }
    // then try finding resources from parent classloaders
    val parentRes = super.findResources(name)
    if (parentRes != null) {
      while (parentRes.hasMoreElements) {
        allRes.add(parentRes.nextElement)
      }
    }

    new util.Enumeration[URL]() {
      private[spark] val it = allRes.iterator

      override def hasMoreElements: Boolean = it.hasNext

      override def nextElement: URL = it.next
    }
  }

  override def getResource(name: String): URL = {
    val resource = if (isSharedClass(name)) parent.getResource(name) else findResource(name)
    if (resource != null) resource else super.getResource(name)
  }

  override def addURL(url: URL): Unit = {
    super.addURL(url)
  }
}

object IsolatedChildFirstClassloader {
  private val defaultConf: KyuubiConf = new KyuubiConf().loadFileDefaults()
  private[kyuubi] val extraSharedClasses =
    defaultConf.get(ENGINE_SPARK_INPROCESS_LAUNCHER_SHARED_CLASS_PREFIX).getOrElse("").split(
      ',').map(_.trim)
}
