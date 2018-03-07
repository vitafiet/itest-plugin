/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Martin 
 * Eigenbrodt, Peter Hayes
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.spirent.plugins.itest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;

/**
 * Saves HTML reports for the project and publishes them.
 * 
 * @author Spirent
 * @since 1.0
 */

public class ReportPublisher {
	private final ArrayList<Report> reportTargets;

	@DataBoundConstructor
	public ReportPublisher(List<Report> reportTargets) {
		this.reportTargets = reportTargets != null 
				? new ArrayList<Report>(reportTargets) 
						: new ArrayList<Report>();
	}

	public ArrayList<Report> getReportTargets() {
		return this.reportTargets;
	}

	private static void writeFile(ArrayList<String> lines, File path) 
			throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
        BufferedWriter bw = new BufferedWriter(w);
		for (int i = 0; i < lines.size(); i++) {
			bw.write(lines.get(i));
			bw.newLine();
		}
		bw.close();
		return;
	}

	public ArrayList<String> readFile(String filePath) 
			throws java.io.FileNotFoundException, java.io.IOException {
		ArrayList<String> aList = new ArrayList<String>();

		try {
			final InputStream is = 
					this.getClass().getResourceAsStream(filePath);
			try {
                final Reader r = new InputStreamReader(is, "UTF-8");
				try {
					final BufferedReader br = new BufferedReader(r);
					try {
						String line = null;
						while ((line = br.readLine()) != null) {
							aList.add(line);
						}
						br.close();
						r.close();
						is.close();
					} finally {
						try {
							br.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				} finally {
					try {
						r.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} finally {
				try {
					is.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		} catch (IOException e) {
			// failure
			e.printStackTrace();
		}

		return aList;
	}

	protected static String resolveParametersInString(AbstractBuild<?, ?> build,
			BuildListener listener, String input) {
		try {
			return build.getEnvironment(listener).expand(input);
		} catch (Exception e) {
			listener.getLogger().println("Failed to resolve parameters in "
					+ "string "+ input + " due to following error:\n" 
					+ e.getMessage());
		}
		return input;
	}

	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, 
			BuildListener listener)
					throws InterruptedException {
		listener.getLogger().println("Archiving HTML reports...");

		// Grab the contents of the header and footer as arrays
		ArrayList<String> headerLines;
		ArrayList<String> footerLines;

		try {
			headerLines = this.readFile(
					"/com/spirent/plugins/itest/HtmlPublisher/header.html");
			footerLines = this.readFile(
					"/com/spirent/plugins/itest/HtmlPublisher/footer.html");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return false;
		} catch (IOException e1) {
			e1.printStackTrace();
			return false;
		}

		for (int i=0; i < this.reportTargets.size(); i++) {
			// Create an array of lines to write out, initially the header
			ArrayList<String> reportLines = new ArrayList<String>(headerLines);
			Report reportTarget = this.reportTargets.get(i); 
			boolean keepAll = reportTarget.getKeepAll();
			boolean allowMissing = reportTarget.getAllowMissing();

            FilePath ws = build.getWorkspace();
            if (ws == null)
                return false;

            String reportDir = resolveParametersInString(build, listener, reportTarget.getReportDir());
            if (reportDir == null)
                return false;

            FilePath archiveDir = ws.child(reportDir);
			FilePath targetDir = reportTarget.getArchiveTarget(build);

			String level = keepAll ? "BUILD" : "PROJECT"; 
			listener.getLogger().println("Archiving at " + level 
					+ " level " + archiveDir + " to " + targetDir);

			// The index name might be a comma separated list 
			String[] csvReports = resolveParametersInString(build, listener, 
					reportTarget.getReportFiles()).split(",");
            //			ArrayList<String> reports = new ArrayList<String>();
			for (int j=0; j < csvReports.length; j++) { 
				String report = csvReports[j];
				report = report.trim();

				// Ignore blank report names caused by trailing/double commas
				if (report.equals("")) {continue;}
                //				reports.add(report);

				String tabNo = "tab" + (j + 1);
				// Make the report name the filename without the extension.
				int end = report.lastIndexOf(".");
				String reportName;
				if (end > 0) {
					reportName = report.substring(0, end);
				} else {
					reportName = report;
				}

				String tabItem = "<li id=\"" + tabNo 
						+ "\" class=\"unselected\" onclick=\"updateBody('" 
						+ tabNo + "');\" value=\"" + report + "\">" + reportName 
						+ "</li>";
				reportLines.add(tabItem);
			}

			// Add the JS to change the link as appropriate.
            String hudsonUrl = null;
            Hudson hubInstance = Hudson.getInstance();
            if (hubInstance != null)
                hudsonUrl = hubInstance.getRootUrl();
			AbstractProject job = build.getProject();
			reportLines.add("<script type=\"text/javascript\">"
					+ "document.getElementById(\"hudson_link\").innerHTML=\""
					+ "Back to " + job.getName() + "\";</script>");
			// If the URL isn't configured in Hudson, the best we can do is
			// attempt to go Back.
			if (hudsonUrl == null) {
				reportLines.add("<script type=\"text/javascript\">"
						+ "document.getElementById(\"hudson_link\").onclick "
						+ "= function() { history.go(-1); return false; };"
						+ "</script>");
			} else {
				String jobUrl = hudsonUrl + job.getUrl();
				reportLines.add("<script type=\"text/javascript\">"
						+ "document.getElementById(\"hudson_link\").href=\"" 
						+ jobUrl + "\";</script>");
			}

			reportLines.add("<script type=\"text/javascript\">"
					+ "document.getElementById(\"zip_link\").href=\"*zip*/" 
					+ reportTarget.getSanitizedName() + ".zip\";</script>");

			try {
				if (!archiveDir.exists() && !allowMissing) {
					listener.error("Specified HTML directory '" + archiveDir 
							+ "' does not exist.");
					build.setResult(Result.FAILURE);
					return true;
				} else if (!keepAll) {
					// We are only keeping one copy at the project level, 
					//so remove the old one.
					targetDir.deleteRecursive();
				}

				if (archiveDir.copyRecursiveTo("**/*", targetDir) == 0 
						&& !allowMissing) {
					listener.error("Directory '" + archiveDir + "' exists but "
							+ "failed copying to '" + targetDir + "'.");
                    Result buildResult = build.getResult();
                    if (buildResult != null && buildResult.isBetterOrEqualTo(Result.UNSTABLE)) {
						// If the build failed, don't complain that there was 
						// no coverage.
						// The build probably didn't even get to the point 
						// where it produces coverage.
						listener.error("This is especially strange since your "
								+ "build otherwise succeeded.");
					}
					build.setResult(Result.FAILURE);
					return true;
				}
			} catch (IOException e) {
				Util.displayIOException(e, listener);
				e.printStackTrace(listener.fatalError(
						"Report publishing failure"));
				build.setResult(Result.FAILURE);
				return true;
			}

			// Add footer 
			reportLines.addAll(footerLines);
			// And write this as the index
			try {
				if(archiveDir.exists())
				{
					reportTarget.handleAction(build);
					writeFile(reportLines, new File(targetDir.getRemote(), 
							reportTarget.getWrapperName()));
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return true;
	}
}
