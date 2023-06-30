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

import java.lang.reflect.Method

class ReflectionUtil(private val className: String, private val classLoader: ClassLoader) {

  def getMethod(name: String, paramTypes: Class[_]*): Method = {
    val javaClass = classLoader.loadClass(className)
    try {
      javaClass.getMethod(name, paramTypes: _*)

    } catch {
      case _: NoSuchMethodException =>
        throw new NoSuchMethodException(
          s"No method $name with parameters $paramTypes found in $className")
    }
  }

  def createInstance(args: Any*): Any = {
    val javaClass = classLoader.loadClass(className)
    val constructor = javaClass.getConstructors()(0)
    constructor.newInstance(args.map(_.asInstanceOf[Object]): _*)
  }

  /*
    TODO, not work for primitive type.
    e.g bool will be translated to Boolean
   */
  def callMethod(instance: Any, name: String, args: Any*): Any = {
    val method = getMethod(name, args.map(_.getClass): _*)
    method.invoke(instance, args.map(_.asInstanceOf[Object]): _*)
  }
}
