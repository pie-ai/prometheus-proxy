#!/bin/sh

docker build  -t de.pa2.prometheus/proxy:build . -f Dockerfile.build

docker create --name extracted de.pa2.prometheus/proxy:build  
docker cp extracted:/prometheus-proxy.jar ./prometheus-proxy.jar
docker rm extracted

docker build  -t de.pa2.prometheus/proxy . -f Dockerfile.runtime