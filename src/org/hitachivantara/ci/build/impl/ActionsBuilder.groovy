/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import groovy.json.JsonSlurper
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder

import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_VERSION

class ActionsBuilder extends MavenBuilder implements IBuilder, Serializable {

  String name = super.getName() // BuildFramework.ACTIONS.name()

  ActionsBuilder(String id, JobItem item) {
    super(id, item)

    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    return super.getExecutionCommand()
  }

  @Override
  Closure getExecution() {
    getBuildClosure(item)
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData['buildData']
    this.steps = builderData['dsl']
  }

  private void triggerActionsBuild(JobItem jobItem, String githubToken) {

    String mvnCmd = super.getCommandBuilder('-DskipTests').build()
    String testMvnCmd = super.getTestCommand()

    Map scmInfo = jobItem.scmInfo

    URL ghApiURL =
        new URL("https://api.github.com/repos/${scmInfo.organization}/${scmInfo.repository}/actions/workflows/tester.yml/dispatches")

    HttpURLConnection triggerConnection = (HttpURLConnection) ghApiURL.openConnection()

    triggerConnection.setRequestProperty("Accept", "application/vnd.github+json")
    triggerConnection.setRequestProperty("Authorization", "Bearer ${githubToken}")
    triggerConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    triggerConnection.setRequestMethod("POST")
    triggerConnection.setDoOutput(true)

    String payload = """{ 
                "ref":"${jobItem.scmBranch}", 
                "inputs":{ 
                  "build_cmd": "${mvnCmd}", 
                  "test_cmd": "${testMvnCmd}",
                  "release_version": "${buildData.getString(RELEASE_VERSION)}",
                  "build_number": "${buildData.getString(RELEASE_BUILD_NUMBER)}"
                } 
              }"""

    OutputStream os = triggerConnection.getOutputStream();
    os.write(payload.getBytes("UTF-8"));
    os.close();

    def httpResponseScanner = new Scanner(triggerConnection.getInputStream())
    while (httpResponseScanner.hasNextLine()) {
      steps.echo httpResponseScanner.nextLine()
    }
    httpResponseScanner.close()

    // The waiting part
    monitorActionsExecution(jobItem, githubToken)
  }

  private void monitorActionsExecution(JobItem jobItem, String githubToken) {

    Map scmInfo = jobItem.scmInfo

    String today = new Date().format("yyyy-MM-dd")
    String actor = steps.utils.getEnvValue("BUILD_GITHUB_USERNAME")

    String query = "actor=${actor}&created=${today}&event=workflow_dispatch&branch=${jobItem.scmBranch}&per_page=50"

    steps.echo "query=${query}"

    URL ghApiURL =
        new URL("https://api.github.com/repos/${scmInfo.organization}/${scmInfo.repository}/actions/runs?${query}")

    String status = ""

    steps.sleep(10) //seconds

    while (status != "completed") {

      HttpURLConnection monitorConnection = (HttpURLConnection) ghApiURL.openConnection()

      monitorConnection.setRequestProperty("Accept", "application/vnd.github+json")
      monitorConnection.setRequestProperty("Authorization", "Bearer ${githubToken}")
      monitorConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

      if (monitorConnection.getResponseCode() == 200) {

        String respJson = monitorConnection.getInputStream().text
        def json = new JsonSlurper().parseText(respJson)

        def run = json.workflow_runs[0]
        status = run.status
        def conclusion = run.conclusion
        def runNumber = run.run_number
        def id = run.id
        steps.echo "Run #${runNumber} with id ${id}, has status of '${status}'. Its conclusion is/was '${conclusion}'"

        // if status is already 'completed', let 's not wait for the sleep
        if (status == "completed") {
          steps.echo "Action built at https://github.com/${scmInfo.organization}/${scmInfo.repository}/actions/runs/${id}"
          break
        }

        steps.sleep(60) //seconds
      } else {
        throw new Exception(monitorConnection.getResponseMessage())
      }
    }
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {

    return { ->

      steps.utils.withStringCredentials('thanos-gh-token', { githubToken ->
        triggerActionsBuild(jobItem, githubToken)

      })
    }
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    return {}
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    return super.expandItem()
  }

  @Override
  void markChanges(JobItem jobItem) {
    super.markChanges(jobItem)
  }

  @Override
  void applyScmChanges() {
    super.applyScmChanges()
  }

  @Override
  Closure getSonarExecution() {
    // not implemented
    return { -> }
  }
}
