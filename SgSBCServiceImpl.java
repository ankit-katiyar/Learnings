package com.rwsol.exemplar.process.sgquoting.impl;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.connecture.doc.DocumentFactory;
import com.connecture.doc.sbc.xml.Plan;
import com.connecture.doc.sbc.xml.PolicyType;
import com.connecture.exemplar.planservice.PlanServiceInputBuilder;
import com.connecture.exemplar.planservice.PlanServiceIntegrationProcess;
import com.connecture.exemplar.planservice.SgPlanServiceInputBean;
import com.connecture.services.planservice.process.PlanView;
import com.connecture.services.planservice.process.StandardInput;
import com.connecture.services.planservice.process.domain.BusinessTypeEnum;
import com.rwsol.exemplar.process.common.ModuleEnabledConfig;
import com.rwsol.exemplar.process.quoteprofile.QuoteProfileData;
import com.rwsol.exemplar.process.quoteprofile.QuoteProfileProcess;
import com.rwsol.exemplar.process.sbc.SbcProcess;
import com.rwsol.exemplar.process.sgquoting.api.SgSBCService;

/**
 * TODO : have this implement an interface so we can generate from external plans
 */
@Service
@Path("/sbc")
public class SgSBCServiceImpl implements SgSBCService
{
  private PlanServiceInputBuilder planServiceInputBuilder;
  private PlanServiceIntegrationProcess planServiceIntegrationProcess;
  private SbcProcess sbcProcess;
  private QuoteProfileProcess quoteProfileProcess;
  private static final Logger LOGGER = Logger.getLogger(SgSBCServiceImpl.class);
  private String planName;
  @Override
  public byte[] generateSgSBC(long quoteId, String productKey) throws Exception
  {
    // 1. get PlanXml from the productKey
    Plan planXML = getSgPlan(quoteId, Long.parseLong(productKey)); 

    // 2. generate the SBC from the PlanXml
    byte[] bytes = DocumentFactory.getInstance().generateDocument(planXML);
    return bytes;
  }

  @GET
  @Path("/sbcgeneration/{quoteId}/{productKey}")
  @Produces({MediaType.APPLICATION_OCTET_STREAM})
  @Transactional
  public Response generateSBC(@PathParam("quoteId")
  long quoteId, @PathParam("productKey")
  String productKey)
  {
    try
    {
      final byte[] bytes = generateSgSBC(quoteId, productKey);
      StreamingOutput output = new StreamingOutput()
        {
          @Override
          public void write(OutputStream output) throws IOException, WebApplicationException
          {
            InputStream fileInput = new ByteArrayInputStream(bytes);
            IOUtils.copy(fileInput, output);
            IOUtils.closeQuietly(fileInput);
            output.flush();
            IOUtils.closeQuietly(output);
          }
        };
      return Response.ok(output, MediaType.APPLICATION_OCTET_STREAM)
        .header("content-disposition", "attachment; filename ="+ planName+".pdf").build();
    }
    catch (Exception e)
    {
      LOGGER.error("Exception encountered while generating sbc byte array " + e.getMessage() + "\n"
        + e.getCause(), e);
      return Response.status(HttpStatus.SC_BAD_REQUEST)
        .entity(buildResponseContent("Failure", e.getMessage())).build();
    }
  }

  /**
   * Constructs the HTTP Response message-body
   * 
   * @return 
   */
  private String buildResponseContent(String statusNode, String descriptionNode)
  {
    StringBuilder content = new StringBuilder();

    content.append("<Response>");

    if (!StringUtils.isEmpty(statusNode))
    {
      content.append("<Status>");
      content.append(statusNode);
      content.append("</Status>");
    }

    if (!StringUtils.isEmpty(descriptionNode))
    {
      content.append("<Description>");
      content.append(descriptionNode);
      content.append("</Description>");
    }

    content.append("</Response>");

    return content.toString();
  }

  /**
   * Returns Plan on the basis of quoteId, productKey.
   * @param quoteId
   * @param productKey
   * @return
   * @throws Exception
   */
  private Plan getSgPlan(long quoteId, long productKey) throws Exception
  {
      Plan planXML = null;
      
      boolean hcrEnabled = false;
      
      List<PlanView> planViews = null;

      SgPlanServiceInputBean sgInputBean = planServiceInputBuilder.buildSgPlanServiceInputBean(quoteId, null);
      StandardInput stdInput = planServiceInputBuilder.buildSgStandardInput(quoteId, BusinessTypeEnum.NEW_BUSINESS);
      planViews = planServiceIntegrationProcess.getSgPlans(Arrays.asList(productKey), sgInputBean, stdInput);
      
      stdInput.getEffectiveDate();
      
      if (planViews != null && !planViews.isEmpty())
      {
          PlanView planView = planViews.get(0);
          this.planName = planView.getDescription().replaceAll("\\s", "_");
          if(ModuleEnabledConfig.isHCREnabled(stdInput.getEffectiveDate()))
          {
            hcrEnabled = true;
          }
          planXML = sbcProcess.getPlanFromPlanView(planView, PolicyType.GROUP, hcrEnabled);
      }

      if (planXML != null)
      {
          QuoteProfileData quoteProfile = quoteProfileProcess.getQuoteProfileData(quoteId);
          if (quoteProfile != null)
          {
              sbcProcess.setPolicyPeriod(planXML, quoteProfile.getRequestedEffectiveDate());
          }
      }

      return planXML;   
  }

  public void setPlanServiceInputBuilder(PlanServiceInputBuilder planServiceInputBuilder)
  {
    this.planServiceInputBuilder = planServiceInputBuilder;
  }

  public void setPlanServiceIntegrationProcess(
    PlanServiceIntegrationProcess planServiceIntegrationProcess)
  {
    this.planServiceIntegrationProcess = planServiceIntegrationProcess;
  }

  public void setSbcProcess(SbcProcess sbcProcess)
  {
    this.sbcProcess = sbcProcess;
  }

  public void setQuoteProfileProcess(QuoteProfileProcess quoteProfileProcess)
  {
    this.quoteProfileProcess = quoteProfileProcess;
  }
  
}