# JetBrains plugin

The [Local S3 plugin](https://plugins.jetbrains.com/plugin/35345-local-s3) runs LocalS3 inside IntelliJ IDEA,
PyCharm, DataGrip, GoLand or any other JetBrains IDE: an S3 service starts when a project is opened and stops with the
IDE, so DuckDB, boto3, the AWS CLI or a Spring Boot application under development have an S3 endpoint without Docker
or any setup. Its sources are at [linux-china/local-s3-jb-plugin](https://github.com/linux-china/local-s3-jb-plugin).

This page is for the users of the plugin. To embed LocalS3 in a plugin of your own, see
[JetBrains IDEs](embedding.md#jetbrains-ides) in the embedding guide.

- [Installation](#installation)
- [The service of an IDE](#the-service-of-an-ide)
- [Settings](#settings)
- [The status bar widget](#the-status-bar-widget)
- [Copy the connection](#copy-the-connection)
- [Project buckets and seed data](#project-buckets-and-seed-data)
- [Big Data Tools](#big-data-tools)

## Installation

The plugin supports the IDEs of 2026.1 and 2026.2, and is installed in one of three ways:

- from the IDE: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd>, search for "Local S3", then
  <kbd>Install</kbd>;
- from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35345-local-s3), with the <kbd>Install to ...</kbd>
  button while the IDE runs;
- from disk: download the [latest release](https://github.com/linux-china/local-s3-jb-plugin/releases/latest), then
  <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>.

The IDE restarts after the installation.

## The service of an IDE

Once a project is opened, the plugin starts LocalS3 in persistent mode, with the defaults below for IntelliJ IDEA:

| Endpoint             | URL                                                    |
|----------------------|--------------------------------------------------------|
| S3 API               | `http://127.0.0.1:39090`                               |
| Console              | `http://127.0.0.1:39090/_admin/ui`                     |
| Iceberg REST catalog | `http://127.0.0.1:39090/iceberg`, when it is enabled   |
| Website hosting      | `http://127.0.0.1:39090/<bucket>/`, for public buckets |

The region is `us-east-1`, the access key ID and the secret access key are both `admin`, and a bucket `demo-bucket` is
created on start. Clients use path-style requests. The [console](deployment.md#console),
[website hosting](semantics.md#static-website-hosting) and the
[Iceberg REST catalog](data-tools.md#the-built-in-iceberg-rest-catalog) behave as in any other LocalS3.

Each IDE product has its own LocalS3, on its own port and with its own data path, so that the buckets of PyCharm don't
show up in IntelliJ IDEA:

| IDE product   | Product code | Default port | Default data path                |
|---------------|--------------|--------------|----------------------------------|
| IntelliJ IDEA | `IU`, `IC`   | `39090`      | `~/.locals3/IU`, `~/.locals3/IC` |
| CLion         | `CL`         | `39091`      | `~/.locals3/CL`                  |
| DataGrip      | `DB`         | `39092`      | `~/.locals3/DB`                  |
| GoLand        | `GO`         | `39093`      | `~/.locals3/GO`                  |
| PyCharm       | `PY`, `PC`   | `39094`      | `~/.locals3/PY`, `~/.locals3/PC` |
| PhpStorm      | `PS`         | `39095`      | `~/.locals3/PS`                  |
| RubyMine      | `RM`         | `39096`      | `~/.locals3/RM`                  |
| RustRover     | `RR`         | `39097`      | `~/.locals3/RR`                  |
| Rider         | `RD`         | `39098`      | `~/.locals3/RD`                  |
| WebStorm      | `WS`         | `39099`      | `~/.locals3/WS`                  |
| Other IDEs    |              | `39100`      | `~/.locals3/<code>`              |

Several instances of the same product, e.g. IntelliJ IDEA 2026.1 and 2026.2 opened at the same time, share one
LocalS3: the first one starts it, and the others reuse it, shown as **Shared** in the status bar. When the IDE that
started it exits, another one takes over on the same port and data path, so the data is kept. Use the same access key
and secret key in all of them, as the shared LocalS3 checks the keys of the IDE that started it.

To share one LocalS3 between different products, set the same port and data path in each of them.

If the port is taken by another process, or the data path is locked by a LocalS3 of another IDE, a notification says
so and links to the settings.

## Settings

<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Local S3</kbd> holds the settings of the service, shared by all the
projects of the IDE. Changing them restarts LocalS3.

| Setting                                      | Default                     | Notes                                                                                                  |
|----------------------------------------------|-----------------------------|--------------------------------------------------------------------------------------------------------|
| Listen address                               | `127.0.0.1`                 | `0.0.0.0` makes LocalS3 reachable from other machines and from containers.                             |
| Port                                         | per product, see above      |                                                                                                        |
| Enable TLS                                   | off                         | See below.                                                                                             |
| Start automatically when a project is opened | on                          | After a manual stop, LocalS3 stays stopped until it is started manually or the IDE restarts.           |
| Data path                                    | per product, see above      | Created when needed.                                                                                   |
| Access Key ID, Secret Access Key             | `admin`, `admin`            | Leave both empty to accept anonymous requests without verifying signatures.                            |
| Buckets                                      | `demo-bucket`               | Comma-separated bucket names, created on start.                                                        |
| Enable Iceberg REST catalog                  | off                         | Served under `/iceberg` on the port of the S3 API.                                                     |
| Warehouse                                    | `s3://warehouse/`           | The location tables are created under. Its bucket is created on start.                                 |

The **Connection** group shows the endpoint, the region and the access key in use, with **Test Connection** and
**Open Admin UI**.

With TLS enabled, the same port answers HTTPS and plain HTTP, see [HTTPS](deployment.md#https). The certificate is
`~/.locals3/tls/locals3.pem` with its key `locals3-key.pem`, shared by all IDEs. The plugin generates a self-signed
one when they are missing and renews it before it expires; replace both files to use another, e.g. one of
[mkcert](https://github.com/FiloSottile/mkcert). Clients must trust the certificate explicitly.

> **Note**: the listen address `0.0.0.0` together with the default `admin` keys, or with no keys at all, lets anyone on
> the network read and write the buckets. Keep `127.0.0.1` unless another machine needs to reach LocalS3.

## The status bar widget

The Local S3 widget in the status bar shows whether LocalS3 is **Running**, **Shared**, **Stopped** or **Failed**, and
its endpoint in its tooltip. A click opens the same menu as <kbd>Tools</kbd> > <kbd>Local S3</kbd>:

- **Start**, **Stop** and **Restart**: **Stop** only stops a LocalS3 that this IDE started;
- **Open Admin UI**, which opens the console in the browser;
- **Copy Connection As...**, see below;
- **Settings...**.

## Copy the connection

**Copy Connection As...** copies to the clipboard what a client needs to reach LocalS3, with the current endpoint and
keys:

| Item                               | What is copied                                                                                  |
|------------------------------------|-------------------------------------------------------------------------------------------------|
| .env                               | `AWS_ENDPOINT_URL`, `AWS_REGION`, the keys, and the same as `S3_*` variables, e.g. for Bun       |
| OpenDAL                            | The `OPENDAL_S3_*` variables, with the first default bucket                                     |
| DuckDB S3 Secret                   | A `CREATE OR REPLACE SECRET` statement of the `httpfs` extension                                |
| Spring Boot application.properties | The `spring.cloud.aws.*` properties of Spring Cloud AWS                                         |
| AWS CLI Profile                    | A `[profile locals3]` section for `~/.aws/config`, used with `aws --profile locals3`            |
| Iceberg: PyIceberg .pyiceberg.yaml | A REST catalog `datalake`                                                                       |
| Iceberg: Spark Properties          | The properties of a Spark catalog `datalake`                                                    |
| Iceberg: DuckDB ATTACH             | The statements that attach the warehouse as the database `datalake`                             |

The Iceberg items are only enabled when the catalog is: the catalog vends the S3 endpoint and the credentials, so its
URI is all these clients need. For DuckDB, with the defaults of IntelliJ IDEA:

```sql
install httpfs; load httpfs;
CREATE OR REPLACE SECRET locals3 (
    TYPE s3,
    KEY_ID 'admin',
    SECRET 'admin',
    REGION 'us-east-1',
    ENDPOINT '127.0.0.1:39090',
    URL_STYLE 'path',
    USE_SSL false
);

SELECT * FROM read_csv('s3://demo-bucket/family.csv');
```

The DuckDB secret always uses plain HTTP, as DuckDB doesn't trust a self-signed certificate. See
[Data tools](data-tools.md) for the other clients.

## Project buckets and seed data

A `.locals3.json` file at the root of a project declares the buckets the project needs and the files to put in them,
so that everyone who opens the project finds the same data in LocalS3. Commit it with the project:

```json
{
  "buckets": [
    "demo-bucket",
    "demo2-bucket"
  ],
  "seed": {
    "demo-bucket": {
      "dataset": "src/test/dataset",
      "family.csv": "family.csv"
    }
  }
}
```

`seed` maps each bucket to its objects: a key is an object path, and its value a local path. When the project is
opened, and every time LocalS3 starts afterward, the buckets are created and the seed files imported:

- a file is imported as the object path, or under it when the object path ends with `/`;
- the files of a directory are imported under the object path, recursively and without hidden files, at most 256 per
  directory;
- local paths are relative to the project root; absolute paths and `~/` are accepted;
- existing objects are overwritten, so that a changed seed file is imported again;
- only into a LocalS3 started by this IDE: a shared one belongs to another process, and a notification tells that the
  file was skipped.

Only the `.locals3.json` of the project root is applied automatically, not those of the modules of a multi-module
project or of other content roots. Right-click any `.locals3.json` in the editor or the Project view and choose
**Sync .locals3.json** to apply it, its local paths then being relative to its own directory.

The editor completes and validates `.locals3.json` with its JSON Schema, reports invalid bucket names as you type, and
completes the local paths, which navigate to their files.

## Big Data Tools

When the Big Data Tools plugin of JetBrains is installed, the plugin adds a connection **Local S3** to it, and keeps
its endpoint, keys and TLS flag in sync with the settings, so the buckets of LocalS3 can be browsed in the Big Data
Tools tool window without configuring a connection.
