import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import CodeBlock from '@theme/CodeBlock';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import HomepageFeatures from '@site/src/components/HomepageFeatures';

import styles from './index.module.css';

const dockerRun = 'docker run -d -p 29090:29090 -e AWS_BUCKETS=my-bucket linuxchina/local-s3';

const junitDependency = `<dependency>
    <groupId>org.mvnsearch</groupId>
    <artifactId>local-s3-jupiter</artifactId>
    <version>last_version</version>
    <scope>test</scope>
</dependency>`;

const junitTest = `@LocalS3
class AppTest {

  @Test
  void test(S3Client s3) {
    s3.createBucket(b -> b.bucket("my-bucket"));
  }
}`;

const springBootTest = `@SpringBootTest
class UploadTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class LocalS3Config {

    @Bean
    @ServiceConnection
    LocalS3Container localS3() {
      return new LocalS3Container("latest").withBuckets("uploads");
    }
  }

  @Autowired
  S3Template s3;

  @Test
  void uploads() {
    s3.upload("uploads", "hello.txt", new ByteArrayInputStream("Hello".getBytes()));
  }
}`;

const javaEmbedded = `try (LocalS3 localS3 = LocalS3.builder().port(29090).build()) {
  localS3.start();
  // point an S3 client at http://localhost:29090, with path-style access
}`;

const useCases = [
  {
    title: 'Tests without an AWS account',
    text: 'Run the S3 code of a service against a real HTTP endpoint with JUnit 5, Testcontainers or Spring Boot.',
  },
  {
    title: 'An S3 server inside your app or IDE',
    text: 'Embed LocalS3 in a Java application or a JetBrains IDE, e.g. as the default S3 of DuckDB.',
  },
  {
    title: 'Lakehouse tests in one process',
    text: 'Point Iceberg, Delta Lake or DuckLake at a fast local S3 with its own catalog built in.',
  },
  {
    title: 'Artifact storage for AI agents',
    text: 'Let an agent keep files and intermediate results through the S3 API or the built-in MCP server.',
  },
];

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero', styles.heroBanner)}>
      <div className="container">
        <img className={styles.heroLogo} src="/img/logo.svg" alt=""/>
        <Heading as="h1" className={styles.heroTitle}>
          {siteConfig.title}
        </Heading>
        <p className={styles.heroSubtitle}>{siteConfig.tagline}</p>
        <p className={styles.heroDetail}>
          Netty-based, no heavy dependencies, starts in moments. S3 API, Vectors, Tables, Iceberg, STS, KMS, website
          hosting and a console, on one port.
        </p>
        <div className={styles.buttons}>
          <Link className="button button--primary button--lg" to="/docs/cheatsheet">
            Get started
          </Link>
          <Link className="button button--secondary button--outline button--lg" to="/docs/comparison">
            Why LocalS3?
          </Link>
        </div>
        <div className={styles.command}>
          <CodeBlock language="bash">{dockerRun}</CodeBlock>
        </div>
      </div>
    </header>
  );
}

function QuickStart() {
  return (
    <section className={styles.quickStart}>
      <div className="container">
        <Heading as="h2" className={styles.sectionTitle}>
          Quick start
        </Heading>
        <Tabs groupId="quick-start">
          <TabItem value="junit" label="JUnit 5" default>
            <CodeBlock language="xml" title="pom.xml">
              {junitDependency}
            </CodeBlock>
            <CodeBlock language="java" title="AppTest.java">
              {junitTest}
            </CodeBlock>
          </TabItem>
          <TabItem value="spring" label="Spring Boot + Testcontainers">
            <CodeBlock language="java" title="UploadTest.java">
              {springBootTest}
            </CodeBlock>
            <p>
              See <Link to="/docs/embedding">Embedding</Link> for the dependencies, Docker Compose and the Spring Boot
              starter.
            </p>
          </TabItem>
          <TabItem value="java" label="Java">
            <CodeBlock language="java">{javaEmbedded}</CodeBlock>
          </TabItem>
          <TabItem value="docker" label="Docker">
            <CodeBlock language="bash">{dockerRun}</CodeBlock>
            <p>
              See <Link to="/docs/deployment">Deployment</Link> for the configuration variables, persistence, HTTPS and
              the executable jar.
            </p>
          </TabItem>
        </Tabs>
      </div>
    </section>
  );
}

function UseCases() {
  return (
    <section className={styles.useCases}>
      <div className="container">
        <Heading as="h2" className={styles.sectionTitle}>
          Built for
        </Heading>
        <div className="row">
          {useCases.map(({title, text}) => (
            <div key={title} className="col col--3">
              <Heading as="h3" className={styles.useCaseTitle}>
                {title}
              </Heading>
              <p>{text}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout title={siteConfig.tagline} description="LocalS3 is an Amazon S3 mock service for testing and local development.">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
        <QuickStart />
        <UseCases />
      </main>
    </Layout>
  );
}
