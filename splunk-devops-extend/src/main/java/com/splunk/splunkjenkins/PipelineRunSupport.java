package com.splunk.splunkjenkins;

import com.cloudbees.workflow.rest.external.*;
import com.splunk.splunkjenkins.console.SplunkTaskListenerFactory;
import com.splunk.splunkjenkins.model.LoggingJobExtractor;
import hudson.Extension;
import hudson.model.Result;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.LabelledChunkFinder;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor;
import io.jenkins.blueocean.rest.impl.pipeline.FlowNodeWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("unused")
@Extension
public class PipelineRunSupport extends LoggingJobExtractor<WorkflowRun> {
    private static final Logger LOG = Logger.getLogger(PipelineRunSupport.class.getName());

    @Override
    public Map<String, Object> extract(WorkflowRun workflowRun, boolean jobCompleted) {
        Map<String, Object> info = new HashMap<String, Object>();
        if (jobCompleted) {
            FlowExecution execution = workflowRun.getExecution();
            if (execution != null) {
                WorkspaceChunkVisitor visitor = new WorkspaceChunkVisitor(workflowRun);
                PipelineNodeGraphVisitor pipelineVisitor = new PipelineNodeGraphVisitor(workflowRun);

                //LabelledChunkFinder to find stages and parallel branches
                ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new LabelledChunkFinder());
                Collection<StageNodeExt> nodes = visitor.getStages();
                Collection<FlowNodeWrapper> pipelineNodes = pipelineVisitor.getPipelineNodes();

                Map<String, String> execNodes = visitor.getWorkspaceNodes();
                Map<String, String> parallelNodeStages = visitor.getParallelNodes();
                if (!nodes.isEmpty()) {
                    List<Map> labeledChunks = new ArrayList<Map>(nodes.size());
                    for (StageNodeExt stageNodeExt : nodes) {
                        for (FlowNodeWrapper nodeFlowNodeWrapper : pipelineNodes) {
                            if (nodeFlowNodeWrapper.getDisplayName().equals(stageNodeExt.getName())
                                    || ("Branch: " + nodeFlowNodeWrapper.getDisplayName()).equals(stageNodeExt.getName())) {
                                Map<String, Object> stage = flowNodeWrapperToMap(nodeFlowNodeWrapper, execNodes);
                                List<Map<String, Object>> children = new ArrayList<>();
                                for (FlowNodeExt childNode : stageNodeExt.getStageFlowNodes()) {
                                    children.add(flowNodeToMap(childNode, execNodes));
                                }
                                if (!children.isEmpty()) {
                                    stage.put("children", children);
                                    if (parallelNodeStages.containsKey(stageNodeExt.getId())) {
                                        stage.put("enclosing_stage", parallelNodeStages.get(stageNodeExt.getId()));
                                    }
                                }
                                labeledChunks.add(stage);
                            }
                        }
                    }
                    info.put("stages", labeledChunks);
                }
            }
            SplunkTaskListenerFactory.removeCache(workflowRun);
        }
        return info;
    }

    /**
     * @param node FlowNodeExt
     * @return a map contains basic info
     */
    private Map<String, Object> flowNodeToMap(FlowNodeExt node, Map<String, String> execNodes) {
        Map<String, Object> result = new HashMap();
        ErrorExt error = node.getError();
        result.put("name", node.getName());
        result.put("id", node.getId());
        result.put("status", toResult(node.getStatus()));
        result.put("duration", node.getDurationMillis() / 1000f);
        result.put("pause_duration", node.getPauseDurationMillis() / 1000f);
        result.put("start_time", node.getStartTimeMillis() / 1000);
        if (error != null) {
            result.put("error", error.getMessage());
            result.put("error_type", error.getType());
        }
        result.put("arguments", node.getParameterDescription());
        String execNodeName = node.getExecNode();
        if (StringUtils.isEmpty(execNodeName)) {
            //lockup the workspace nodes
            execNodeName = execNodes.get(node.getId());
        }
        result.put("exec_node", execNodeName);
        return result;
    }

    /**
     * @param node FlowNodeWrapper
     * @return a map contains basic info
     */
    private Map<String, Object> flowNodeWrapperToMap(FlowNodeWrapper node, Map<String, String> execNodes) {
        Map<String, Object> result = new HashMap();
        result.put("name", node.getDisplayName());
        result.put("id", node.getId());
        result.put("status", node.getStatus().getResult().toString());
        result.put("duration", node.getTiming().getTotalDurationMillis() / 1000f);
        result.put("pause_duration", node.getTiming().getPauseDurationMillis() / 1000f);
        result.put("start_time", node.getTiming().getStartTimeMillis() / 1000f);
        if (!(StringUtils.isEmpty(node.getCauseOfFailure()))) {
            result.put("error", node.getCauseOfFailure());
            result.put("error_type", "error");
        }
        String execNodeName = node.getNode().getDisplayName();
        if (StringUtils.isEmpty(execNodeName)) {
            //lockup the workspace nodes
            execNodeName = execNodes.get(node.getId());
        }
        result.put("exec_node", execNodeName);
        return result;
    }
    /**
     * @param status
     * @return String compatible with hudson.model.Result
     */
    private String toResult(StatusExt status) {
        if (status == null) {
            return "UNKNOWN";
        }
        switch (status) {
            case FAILED:
                return Result.FAILURE.toString();
            case NOT_EXECUTED:
                return Result.NOT_BUILT.toString();
            default:
                return status.toString();
        }
    }
}
