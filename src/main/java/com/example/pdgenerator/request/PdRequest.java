package com.example.pdgenerator.request;

public class PdRequest {
    private String jobSeries;
    private String subJobSeries;
    private String federalAgency;
    private String subOrganization;
    private String lowestOrg;
    private String historicalData;
    private String samplePD;
    private String gsGrade;
    private String supervisoryLevel;
    private String gradeRange;
    private Integer totalPoints;

    // Getters and Setters
    public String getJobSeries() {
        return jobSeries;
    }

    public void setJobSeries(String jobSeries) {
        this.jobSeries = jobSeries;
    }

    public String getSubJobSeries() {
        return subJobSeries;
    }

    public void setSubJobSeries(String subJobSeries) {
        this.subJobSeries = subJobSeries;
    }

    public String getFederalAgency() {
        return federalAgency;
    }

    public void setFederalAgency(String federalAgency) {
        this.federalAgency = federalAgency;
    }

    public String getSubOrganization() {
        return subOrganization;
    }

    public void setSubOrganization(String subOrganization) {
        this.subOrganization = subOrganization;
    }

    public String getLowestOrg() {
        return lowestOrg;
    }

    public void setLowestOrg(String lowestOrg) {
        this.lowestOrg = lowestOrg;
    }

    public String getHistoricalData() {
        return historicalData;
    }

    public void setHistoricalData(String historicalData) {
        this.historicalData = historicalData;
    }

    public String getSamplePD() {
        return samplePD;
    }

    public void setSamplePD(String samplePD) {
        this.samplePD = samplePD;
    }

    public String getGsGrade(){
        return gsGrade;
    }

    public void setGsGrade(String gsGrade){
        this.gsGrade = gsGrade;
    }

    public String getSupervisoryLevel() {
        return supervisoryLevel;
    }

    public void setSupervisoryLevel(String supervisoryLevel) {
        this.supervisoryLevel = supervisoryLevel;
    }

    public String getGradeRange() {
        return gradeRange;
    }

    public void setGradeRange(String gradeRange) {
        this.gradeRange = gradeRange;
    }

    public Integer getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(Integer totalPoints) {
        this.totalPoints = totalPoints;
    }
}
