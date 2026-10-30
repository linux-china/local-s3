import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  to: string;
  description: ReactNode;
};

const FeatureList: FeatureItem[] = [
  {
    title: '90+ S3 operations',
    to: '/docs/apis',
    description: (
      <>
        Objects, multipart uploads, versioning, tagging, ACLs, CORS, policies, browser form uploads and paginated
        listings, with SigV4 verification and path-style or virtual-hosted-style requests.
      </>
    ),
  },
  {
    title: 'Faithful semantics',
    to: '/docs/semantics',
    description: (
      <>
        Conditional reads, writes and deletes that are atomic per key, Amazon S3 entity tags for multipart uploads,
        and the validation of Amazon S3 for bucket names and part sizes.
      </>
    ),
  },
  {
    title: 'Iceberg REST catalog & S3 Tables',
    to: '/docs/data-tools#the-built-in-iceberg-rest-catalog',
    description: (
      <>
        A built-in Iceberg REST catalog and Amazon S3 Tables on the same port, so a lakehouse test needs one process.
        Delta Lake, DuckDB, DuckLake, Paimon and Hudi work too.
      </>
    ),
  },
  {
    title: 'S3 Vectors',
    to: '/docs/apis',
    description: <>Vector buckets, indexes and similarity search, reached with the AWS SDK's S3VectorsClient.</>,
  },
  {
    title: 'Embed it anywhere',
    to: '/docs/embedding',
    description: (
      <>
        A Java API, a JUnit 5 extension, a Spring Boot 3 and 4 starter, a Testcontainers module, a Docker image (JVM
        or GraalVM native) or an executable jar.
      </>
    ),
  },
  {
    title: 'JetBrains plugin',
    to: '/docs/jetbrains-plugin',
    description: (
      <>
        An S3 service that starts with IntelliJ IDEA, PyCharm or DataGrip, with project buckets and seed data from{' '}
        <code>.locals3.json</code>, and connection settings copied for DuckDB, boto3, Spark or the AWS CLI.
      </>
    ),
  },
  {
    title: 'Memory or disk',
    to: '/docs/deployment#configuration',
    description: (
      <>
        In-memory and persistent modes, and in-memory services that start from a fixture directory. Data directories
        of hundreds of thousands of objects open without loading all their metadata.
      </>
    ),
  },
  {
    title: 'STS, KMS & S3 Express',
    to: '/docs/embedding#temporary-credentials-sts',
    description: (
      <>
        Stateless STS and KMS endpoints, and S3 Express One Zone directory buckets, so credential-vending catalogs and
        encryption clients run unchanged.
      </>
    ),
  },
  {
    title: 'Console, website & MCP',
    to: '/docs/deployment#console',
    description: (
      <>
        A built-in console at <code>/_admin/ui</code>, static website hosting, and an MCP server at <code>/_mcp</code>{' '}
        so AI agents can keep their artifacts in LocalS3.
      </>
    ),
  },
];

function Feature({title, to, description}: FeatureItem) {
  return (
    <Link to={to} className={styles.feature}>
      <Heading as="h3" className={styles.featureTitle}>
        {title}
      </Heading>
      <p className={styles.featureText}>{description}</p>
    </Link>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <Heading as="h2" className={styles.sectionTitle}>
          Everything a test expects from S3
        </Heading>
        <div className={styles.grid}>
          {FeatureList.map((props) => (
            <Feature key={props.title} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
