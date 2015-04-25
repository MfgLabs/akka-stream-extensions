---
layout: post
title: Unleashing Akka Stream Extensions
---


Today, we are proud to release this opensource library extending the very promising & already great [Typesafe Akka-Stream](http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0-RC1/scala.html?_ga=1.42749861.1204922152.1421451776).

For the impatient, if you want to use it immediately, let's go to our [Readme page](/akka-stream-extensions/readme/)

## Introduction

Our main purpose in this project is:

1. to gather generic structures, low-level or higher-level built on top of existing Akka-Stream `Sources`/`Flows`/`Sinks`, well tested, production ready that can be reused in your projects

2. to study/evaluate bleeding-edge concepts based on Akka-Stream

At [MfgLabs](http://mfglabs.com), we have been developing this library for our production projects because we have identified a few primitives that were common to many use-cases, not provided by Akka-Stream out of the box and not so easy to implement in a robust way.

We thank [MfgLabs](http://mfglabs.com) for accepting to opensource this library under [Apache V2](http://www.apache.org/licenses/LICENSE-2.0.html) but we believe it can be very useful & interesting to many people and we are sure some will help us debug & build more useful structures.

> So don't hesitate to [contribute](/akka-stream-extensions/contributing/)

## What's provided?

TODO