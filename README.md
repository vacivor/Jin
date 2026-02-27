# jin-web

## 本地构建

```bash
./gradlew clean build
```

## 发布

发布到本地 Maven：

```bash
./gradlew publishToMavenLocal
```

发布到 Nexus（默认快照库/正式库）：

```bash
./gradlew publish \
  -PnexusUsername=<username> \
  -PnexusPassword=<password>
```

可选参数：

- `-Pjin.version=1.0.0`：覆盖发布版本
- `-PnexusSnapshotsUrl=...`：覆盖 snapshots 仓库地址
- `-PnexusReleasesUrl=...`：覆盖 releases 仓库地址
- 环境变量 `NEXUS_USERNAME` / `NEXUS_PASSWORD` 也可作为凭据来源
