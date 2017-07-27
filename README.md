# prometheus-proxy
proxy http server that fetched prometheus metrics remotely

To connect remote servers, running for example
* Container Exporter https://github.com/docker-infra/container_exporter
* cAdvisor https://github.com/google/cadvisor
* or any other prometheus data source.


you may not be able to connect directly. This little dirty proxy creates a frontend for multiple prometheus backends locally.

At the moment there are following implementations:
* http: intergrate remote url locally (for example http://demo.robustperception.io:9100/metrics)
* ssh+http: connect to remote ssh server and integrate http://127.0.0.1:9100/metrics

During development I learned a lot about prometheus, I hope that helps someone else ;-) Corrections or enhancements? Pull requests are welcome!


## Example
### Build and Run
```
$ git clone https://github.com/pie-ai/prometheus-proxy.git
$ cd prometheus-proxy
```
#### using docker
```
$ ./build.sh
...
Successfully tagged de.pa2.prometheus/proxy:latest
$ PWD=$(pwd)
$ docker run -d -p 18080:18080 -v/srv/prometheus-proxy/conf:${PWD}/config de.pa2.prometheus/proxy:latest
```
and open http://localhost:18080

#### using maven / java (8)
```
$ mvn clean install
$ PWD=$(pwd)
$ java -jar target/prometheus-proxy.jar ${PWD}/config
```

### Prometheus configuration
prometheus.yml
```
  - job_name: 'remote-application.de'
    # context deadline exceeded -> https://github.com/prometheus/prometheus/issues/1438
    scrape_interval: 360s
    scrape_timeout: 60s
    metrics_path: /live/remote-application.de/metrics
    static_configs:
      - targets: ['prometheus-proxy.docker.internal']
        labels:
          group: 'nodes'
    relabel_configs:
    - source_labels: ['job']
      target_label: 'instance'
```
### Proxy Configuration
/config/live/remote-application.de/configuration.properties
```
mode=ssh+http
host=123.123.123.123
port=22
user=user
key=id_rsa

url=http://127.0.0.1:9100/metrics
```
