# Publishing to GitHub Packages

Repository: https://github.com/SWAT-project/cfg-extraction

## Setup for Publishing

1. **Create GitHub Token**:
   - Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
   - Generate new token with `write:packages` and `read:packages` permissions
   - Save the token securely

3. **Set credentials** (choose one method):

   **Option A: Environment variables** (recommended for CI/CD)
   ```bash
   export GITHUB_ACTOR=nils-loose
   export GITHUB_TOKEN=your-github-token
   ```

   **Option B: gradle.properties** (for local development)
   Create `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=nils-loose
   gpr.token=your-github-token
   ```

2. **Publish the package**:
   ```bash
   ./gradlew publish
   ```

## Consuming from Another Project

In your other project's `build.gradle`:

```groovy
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/SWAT-project/cfg-extraction")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'com.cfgextractor:cfg-extractor:1.0-SNAPSHOT'
}
```

## Using as a Command-Line Tool

If you want to use it as a CLI tool from the consuming project:

```groovy
configurations {
    cfgExtractorTool
}

dependencies {
    cfgExtractorTool 'com.cfgextractor:cfg-extractor:1.0-SNAPSHOT'
}

task extractCFG(type: JavaExec) {
    classpath = configurations.cfgExtractorTool
    mainClass = 'com.cfgextractor.Main'
    args = ['target/', 'output/', 'Main', 'main', 'inter']
}
```

Then run:
```bash
./gradlew extractCFG
```

## Version Management

To publish a new version:
1. Update version in `build.gradle`: `version = '1.0.0'` (remove SNAPSHOT for releases)
2. Commit and tag: `git tag v1.0.0 && git push --tags`
3. Publish: `./gradlew publish`

## GitHub Actions CI/CD (Optional)

Create `.github/workflows/publish.yml`:

```yaml
name: Publish Package

on:
  release:
    types: [created]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
    - uses: actions/checkout@v3
    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'

    - name: Publish package
      run: ./gradlew publish
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

This will automatically publish when you create a GitHub release.
