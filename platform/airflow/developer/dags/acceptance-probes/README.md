# Acceptance probe mount point

The canonical live suite mounts its retry and Deadline Alert probe DAG directory here. Keeping
the target directory in the base DAG tree allows Docker to layer that read-only bind mount beneath
the deployment's read-only `/opt/airflow/dags` mount.
