#!/bin/sh

#ls -la /bin
#find /srv

DIR="."

echo "env:"
env

# envrinoment check
# docker or development run?
if [ -f ${DIR}/prometheus-proxy.jar ]; then
	echo "development envrinoment"
	DEV=true
else
	echo "docker envrinoment"
	DEV=false
fi


###
# create classpath
###
JAVA_CP=""
if ${DEV}; then
	JAVA_CP="${DIR}/prometheus-proxy.jar";
else
	JAVA_CP="/srv/prometheus-proxy/lib/prometheus-proxy.jar";	
fi

# todo: include conf or etc folder for configuration into classpath to allow logback customization

###
# set config parameter
###
CONFIG=""
if ${DEV}; then
	CONFIG="${DIR}/config";
else
	CONFIG="/srv/prometheus-proxy/conf";	
fi

MAIN_CLASS="de.pa2.prometheus.proxy.SimpleProxy"

java -cp ${JAVA_CP} ${MAIN_CLASS} ${CONFIG}