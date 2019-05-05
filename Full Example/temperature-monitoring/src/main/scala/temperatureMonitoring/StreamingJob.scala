/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package temperatureMonitoring

import org.apache.flink.core.fs.FileSystem
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector
import org.fiware.cosmos.orion.flink.connector.OrionSource


object StreamingJob {

  var roomStateMinMax: Map[String, Int] = Map() // 0: OK, 1: under min, 2: over max
  var roomStateRising: Map[String, Int] = Map() // 0: OK, 1: temp rising
  var sensorState: Map[String, Int] = Map() // 0: no timeout, 1: timeout
  var timerMap: Map[String,Long] = Map() // holds the last recorded system time for each sensor

  def timeoutWriter(sensor: String, sensorTimer: Long, currentTime: Long): String = {
    if (currentTime - sensorTimer > 10 && (sensorState.getOrElse(sensor, 0) != 1)){
      sensorState += (sensor -> 1)
      sensor + ": timeout! please check if the sensor is still working! %0A"
    } else if (currentTime - sensorTimer <= 10 && (sensorState.getOrElse(sensor, 0) == 1)) {
      sensorState += (sensor -> 0)
      sensor + ": Sensor is working again! %0A"
    } else {
      ""
    }
  }
  def getTimeouts(map: Map[String, Long], time: Long): String = {
    var text: String = ""
    map foreach (x => text += timeoutWriter(x._1, x._2, time))
    text
  }
  def checkTime(sensor: String): String = {
    val currentTime: Long = System.currentTimeMillis() / 1000
    timerMap += (sensor -> currentTime)
    var message: String = getTimeouts(timerMap, currentTime)
    message = message.stripLineEnd
    if (message != "") {
      // in urlString replace INSERT_BOT_KEY with your actual bot key from Botfather and CHANNEL with your channel id
      val urlString: String = "https://api.telegram.org/botINSERT_BOT_KEY/sendMessage?chat_id=@CHANNEL&text=" + message
      scala.io.Source.fromURL(urlString)
    }
    message
  }

  def checkTemp(tuple: (String, String)): String = {
    var message: String = ""
    val currTemp: Float = tuple._2.toFloat
    if (roomStateMinMax.getOrElse(tuple._1, 0) == 0){
      if (currTemp >= 50f) {
        message = tuple._1 + ": Room is on fire! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 2)
      } else if (currTemp <= 15f) {
        message = tuple._1 + ": Room is too cold! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 1)
      }
    } else if (roomStateMinMax.getOrElse(tuple._1, 0) == 1) {
      if (15f < currTemp && currTemp < 50f) {
        message = tuple._1 + ": Room temperature is fine again! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 0)
      } else if (currTemp >= 50f) {
        message = tuple._1 + ": Room is on fire! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 2)
      }
    } else {
      if (15f < currTemp && currTemp < 50f) {
        message = tuple._1 + ": Room temperature is fine again! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 0)
      } else if (currTemp <= 15f) {
        message = tuple._1 + ": Room is too cold! Temperature: " + tuple._2 + "°C"
        roomStateMinMax += (tuple._1 -> 1)
      }
    }
    message = message.stripLineEnd
    if (message != "") {
      // in urlString replace INSERT_BOT_KEY with your actual bot key from Botfather and CHANNEL with your channel id
      val urlString: String = "https://api.telegram.org/botINSERT_BOT_KEY/sendMessage?chat_id=@CHANNEL&text=" + message
      scala.io.Source.fromURL(urlString)
    }
    message
  }

  def main(args: Array[String]) {
    // set up the streaming execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val eventStream = env.addSource(new OrionSource(9001))

    val timeoutDataStream = eventStream.flatMap(event => event.entities)
      .map(entity => entity.id)
      .map(checkTime(_))

    val filteredTimeoutStream = timeoutDataStream.filter(_ != "")
    filteredTimeoutStream.writeAsText("/tmp/logtimeout.txt", FileSystem.WriteMode.OVERWRITE)

    val minMaxDataStream = eventStream.flatMap(event => event.entities)
      .map(entity => (entity.id, entity.attrs("temperature").value.asInstanceOf[String]))
      .map(checkTemp(_))

    val filteredMinMaxStream = minMaxDataStream.filter(_ != "")
    filteredMinMaxStream.writeAsText("/tmp/logminmax.txt", FileSystem.WriteMode.OVERWRITE)

    val windowedDataStream = eventStream.flatMap(event => event.entities)
      .map(entity => (entity.id, entity.attrs("temperature").value.asInstanceOf[String]))
      .keyBy(_._1)
      .window(SlidingProcessingTimeWindows.of(Time.seconds(20), Time.seconds(10)))
      .process(new MyProcessWindowFunction)

    val filteredWindowedStream = windowedDataStream.filter(_ != "")
    filteredWindowedStream.writeAsText("/tmp/logtemprise.txt", FileSystem.WriteMode.OVERWRITE)


    env.execute("Flink Streaming Scala API Skeleton")
  }

  class MyProcessWindowFunction extends ProcessWindowFunction[(String, String), String, String, TimeWindow] {

    override def process(key: String, context: Context, elements: Iterable[(String, String)], out: Collector[String]): Unit = {
      var message: String = ""
      val temperatures: Iterable[Float] = elements.map(_._2.toFloat)
      val difference: Float = temperatures.last - temperatures.head

      if(roomStateRising.getOrElse(key, 0) == 0){
        if (difference > 3f){
          message = key + ": Room on fire! Temperature is rising too fast!"
          roomStateRising += (key -> 1)
        }
      } else {
        if (difference <= 3f){
          message = key + ": Temperature is not rising too fast anymore!"
          roomStateRising += (key -> 0)
        }
      }

      if (message != "") {
        // in urlString replace INSERT_BOT_KEY with your actual bot key from Botfather and CHANNEL with your channel id
        val urlString: String = "https://api.telegram.org/botINSERT_BOT_KEY/sendMessage?chat_id=@CHANNEL&text=" + message
        scala.io.Source.fromURL(urlString)
      }

      out.collect(message)
    }
  }
}
