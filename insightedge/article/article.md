In this demo/blog post we will show you how to combine real-time speech recognition with real-time speech classification based on Intel's BigDL library and Insightedge.

## What is BigDL?
BigDL is a distributed deep learning library for Apache Spark. You can lean more about deep learning and neural networks on [Coursera](https://www.coursera.org/specializations/deep-learning).

With BigDL it's possible write deep learning applications as standard Spark programs thus allowing to leverage Spark during model training, prediction, and tuning. High performance and throughput is achieved with [Intel Math Kernel Library](https://software.intel.com/en-us/mkl). Read more about BigDL [here](https://github.com/intel-analytics/BigDL).  

## Motivation
As for example let's consider big companies with huge client base requires to organize call centers. In order to service client correctly it's vital to which specialist he should be directed. Current demo takes advantage of cutting edge technologies to resolve such tasks in effective manner less then in 100ms.
Here is a general workflow:

![Application flow](flow.png)


## Architecture
Let's take a helicopter view of the application components.

![Architecture](architecture.png)


## How to run

Used software:
* scala v2.10.4
* java 1.8.x
* kafka v0.8.2.2
* insightedge v1.0.0
* BigDL v0.2.0
* sbt
* maven v3.x

Prerequisites:
* Download and extract data(first three steps) as described [here](https://github.com/intel-analytics/BigDL/tree/master/spark/dl/src/main/scala/com/intel/analytics/bigdl/example/textclassification)
* Set INSIGHTEDGE_HOME and KAFKA_HOME env variables
* Make sure you have Scala installed: ```scala -version```
* Change variables according to your needs in runModelTrainingJob.sh, runTextPredictionJob.sh, runKafkaProducer.sh

Running demo is divided in three parts:

1. Build project and start components
    * Clone this repo
    * Go to insightedge directory: ```cd BigDL/insightedge```
    * Build the project: ```sh build.sh```
    * Start zookeeper and kafka server: ```sh kafka-start.sh```
    * Create Kafka topic: ```sh kafka-create-topic.sh```. To verify that topic was created run ```sh kafka-topics.sh```
    * Start Insightedge in demo mode: ```sh ie-demo.sh```
    * Deploy processor-0.2.0-jar-with-dependencies.jar in GS UI.

2. Train BigDL model
    * Train text classifier model: ```sh runModelTrainingJob.sh```

3. Run Spark streaming job with trained BigDL classification model
    * In separate terminal tab start Spark streaming for predictions: ```sh runTextClassificationJob.sh```.
    * Start web server: ```cd BigDL/web and sh runWeb.sh```.

Now go to [https://localhost:9443](https://localhost:9443):

1. Click on a microphone button and start talking. Click microphone button one more time to stop recording and send speech to Kafka.
2. Shortly you will see a new record in "In-process calls" table. It means that call is currently processed.
3. After a while row from "In-process call" table will be moved to the "Call sessions" table. In column "Category" you can see to which category speech was classified by BigDL model. In column "Time" you will see how much time in milliseconds it took to classify the speech. 

## Shutting down:
* Stop kafka: ```sh kafka-stop.sh```
* Stop Insightedge: ```sh ie-shutdown.sh```