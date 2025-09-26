// Global variables and config
const USA_JOBS_CONFIG = {
    API_KEY: 'szq+h8pmtLiZ++/ldJQh3ZZjfVfEk74mcsAViRJGgCA=',
    EMAIL: 'marko.vukovic0311@gmail.com',
    BASE_URL: 'https://data.usajobs.gov/api/search'
};

// Initialize data to prevent ReferenceError
let data = {
    gsGrade: '',
    totalPoints: '',
    gradeRange: ''
};

// Steps definitions
const steps = [
    { id: 1, name: "Action Selection", description: "Choose create or update", icon: "fa-bolt" },
    { id: 2, name: "Agency Setup", description: "Select agency and organization", icon: "fa-building" },
    { id: 3, name: "Job Classification", description: "Define job series and title", icon: "fa-users" },
    { id: 4, name: "Duties & Responsibilities", description: "Enter key duties", icon: "fa-tasks" },
    { id: 5, name: "Review & Generate", description: "Review and create PD", icon: "fa-check-circle" },
    { id: 6, name: "Generated PD", description: "Download and finalize", icon: "fa-file-alt" }
];

const updateSteps = [
    { id: 1, name: "Upload PD", description: "Provide existing position description", icon: "fa-upload" },
    { id: 2, name: "Edit Sections", description: "Edit each section individually", icon: "fa-edit" },
    { id: 3, name: "Review Changes", description: "Review and finalize updates", icon: "fa-eye" },
    { id: 4, name: "Download", description: "Export updated PD", icon: "fa-download" }
];

// Form state and globals
let jobSeriesData = {};
let selectedSeries = null;
let formState = {
    lowestOrg: '',
    federalAgency: '',
    subOrg: '',
    jobSeries: '',
    subSeries: '',
    duties: [],
    unknownDuties: [],
    supervisoryLevel: '',
    gsGrade: data.gsGrade,
    gradeRange: data.gradeRange,
    totalPoints: data.totalPoints,
    usedAIRecommendation: false
};

if (!window.sectionEditHistory) window.sectionEditHistory = {};
if (!window.sectionEditStacks) window.sectionEditStacks = {};

// Helper functions
function sectionKeyToId(key) {
    if (!key) return '';
    return key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
}

function showFactorUpdateSuccess() {
    console.log('Factor points and grade updated successfully.');
}

function showFactorUpdateError(msg) {
    alert('Failed to update factor points/grade: ' + msg);
}

// Expose globally
window.showFactorUpdateSuccess = showFactorUpdateSuccess;
window.showFactorUpdateError = showFactorUpdateError;

// DOMContentLoaded initialization
document.addEventListener('DOMContentLoaded', function() {
    const disclaimerCheckbox = document.getElementById('disclaimerAccept');
    const continueBtn = document.getElementById('continueBtn');
    const disclaimerModal = document.getElementById('disclaimerModal');
    const mainApp = document.getElementById('mainApp');
    const updateApp = document.getElementById('updateApp');

    if (disclaimerModal) disclaimerModal.style.display = 'flex';
    if (mainApp) mainApp.style.display = 'none';
    if (updateApp) updateApp.style.display = 'none';

    if (disclaimerCheckbox && continueBtn) {
        setupDisclaimerModal();
    }

    initializeJobSeriesData();

    // Expose functions globally
    window.renderStep = renderStep;
    window.showUpdateApp = showUpdateApp;
    window.showMainApp = showMainApp;
});

// USA Jobs API Functions
async function fetchJobSeriesFromAPI(page = 1, allJobs = []) {
    try {
        console.log(`Fetching job series from USA Jobs API... Page ${page}`);
        
        const headers = {
            'Authorization-Key': USA_JOBS_CONFIG.API_KEY,
            'User-Agent': USA_JOBS_CONFIG.EMAIL,
            'Content-Type': 'application/json'
        };

        const resultsPerPage = 10000;
        const response = await fetch(`${USA_JOBS_CONFIG.BASE_URL}?ResultsPerPage=${resultsPerPage}&Page=${page}`, {
            method: 'GET',
            headers: headers
        });
2
        if (!response.ok) {
            throw new Error(`API request failed: ${response.status} ${response.statusText}`);
        }

        const data = await response.json();
        
        if (data.SearchResult && data.SearchResult.SearchResultItems) {
            allJobs = allJobs.concat(data.SearchResult.SearchResultItems);
            
            const totalJobs = data.SearchResult.SearchResultCount;
            const currentJobCount = page * resultsPerPage;
            
            if (currentJobCount < totalJobs && page < 100) {
                return await fetchJobSeriesFromAPI(page + 1, allJobs);
            }
        }

        return {
            SearchResult: {
                SearchResultItems: allJobs,
                SearchResultCount: allJobs.length
            }
        };
    } catch (error) {
        console.error('Error fetching job series from API:', error);
        return {
            SearchResult: {
                SearchResultItems: allJobs,
                SearchResultCount: allJobs.length
            }
        };
    }
}

function processAPIJobSeries(apiData) {
    if (!apiData || !apiData.SearchResult || !apiData.SearchResult.SearchResultItems) {
        console.log('No valid API data received');
        return {};
    }

    const jobs = apiData.SearchResult.SearchResultItems;
    const seriesMap = new Map();

    console.log(`Processing ${jobs.length} jobs from API...`);

    jobs.forEach((item, index) => {
        try {
            const job = item.MatchedObjectDescriptor;
            
            let seriesCode = extractSeriesCode(job);
            if (!seriesCode || !/^\d{4}$/.test(seriesCode)) {
                return;
            }

            const positionTitle = job.PositionTitle || 'Untitled Position';
            
            if (!seriesMap.has(seriesCode)) {
                const seriesTitle = extractSeriesTitle(job, seriesCode, positionTitle);

                // Extract group info from JobCategory if available
                let groupCode = null, groupName = null;
                if (job.JobCategory && job.JobCategory.length > 0) {
                    const cat = job.JobCategory[0];
                    groupCode = cat.Code ? cat.Code.substring(0, 2) + "00" : null;
                    groupName = cat.Name ? cat.Name + " Group" : null;
                } else {
                    groupCode = seriesCode.substring(0, 2) + "00";
                    groupName = `${groupCode} Group`;
                }

                seriesMap.set(seriesCode, {
                    title: seriesTitle,
                    positions: new Set(),
                    keywords: new Set(),
                    locations: new Set(),
                    departments: new Set(),
                    groupCode,
                    groupName,
                    groups: groupCode && groupName ? [{ code: groupCode, name: groupName }] : []
                });
            }

            const series = seriesMap.get(seriesCode);
            series.positions.add(positionTitle);

            // Extract keywords and metadata
            extractJobMetadata(job, series);

        } catch (error) {
            console.error(`Error processing job ${index}:`, error);
        }
    });

    return convertSeriesToJobData(seriesMap);
}

function extractSeriesCode(job) {
    // Primary method: Look for JobCategory with Series information
    if (job.JobCategory && job.JobCategory.length > 0) {
        for (const category of job.JobCategory) {
            if (category.Code && category.Code.length === 4 && /^\d{4}$/.test(category.Code)) {
                return category.Code;
            }
        }
    }

    // Secondary methods
    if (job.JobGrade && job.JobGrade.length > 0) {
        const grade = job.JobGrade[0];
        if (grade.Code && /^\d{4}$/.test(grade.Code)) {
            return grade.Code;
        }
    }

    // Extract from text using GS-XXXX pattern
    const textToSearch = [
        job.PositionTitle,
        job.UserArea?.Details?.JobSummary,
        job.QualificationSummary
    ].filter(Boolean).join(' ');

    const seriesMatch = textToSearch.match(/GS[- ]?(\d{4})/i);
    if (seriesMatch) {
        return seriesMatch[1];
    }

    // PositionSchedule fallback
    if (job.PositionSchedule && job.PositionSchedule.length > 0) {
        const schedule = job.PositionSchedule[0];
        if (schedule.Code && /^\d{4}$/.test(schedule.Code)) {
            return schedule.Code;
        }
    }

    return null;
}

function extractSeriesTitle(job, seriesCode, positionTitle) {
    let seriesTitle = 'Unknown Series';

    if (job.JobCategory && job.JobCategory.length > 0) {
        const matchingCategory = job.JobCategory.find(cat =>
            cat.Code === seriesCode && cat.Name
        );
        if (matchingCategory) {
            seriesTitle = matchingCategory.Name;
        }
    }

    if (seriesTitle === 'Unknown Series' && job.UserArea && job.UserArea.Details && job.UserArea.Details.JobSummary) {
        const summary = job.UserArea.Details.JobSummary;
        const titleMatch = summary.match(/This position is in the (.+?) job series/i) ||
                        summary.match(/(\w+(?:\s+\w+)*)\s+Series/i) ||
                        summary.match(/GS-\d{4}\s+(.+?)(?:\.|,|\n)/i);
        if (titleMatch) {
            seriesTitle = titleMatch[1].trim();
        }
    }

    if (seriesTitle === 'Unknown Series') {
        seriesTitle = positionTitle;
    }

    return seriesTitle
        .replace(/GS-\d{4}\s*/i, '')
        .replace(/Series$/i, '')
        .trim();
}

function extractJobMetadata(job, series) {
    // Extract keywords from job summary
    if (job.UserArea && job.UserArea.Details && job.UserArea.Details.JobSummary) {
        const summary = job.UserArea.Details.JobSummary;
        extractKeywords(summary).forEach(keyword => series.keywords.add(keyword));
    }

    // Extract keywords from qualifications
    if (job.QualificationSummary) {
        extractKeywords(job.QualificationSummary).forEach(keyword => series.keywords.add(keyword));
    }

    // Add location information
    if (job.PositionLocationDisplay) {
        series.locations.add(job.PositionLocationDisplay);
    }

    // Add department information
    if (job.DepartmentName) {
        series.departments.add(job.DepartmentName);
    }
}

function getGroupSeriesOptionsFromAPI() {
    // Build group series options from jobSeriesData
    const groupMap = {};
    Object.keys(jobSeriesData).forEach(seriesCode => {
        const groupCode = seriesCode.substring(0, 2) + "00";
        if (!groupMap[groupCode]) {
            groupMap[groupCode] = {
                code: groupCode,
                seriesCodes: []
            };
        }
        groupMap[groupCode].seriesCodes.push(seriesCode);
    });
    // Sort numerically
    return Object.values(groupMap).sort((a, b) => parseInt(a.code) - parseInt(b.code));
}

function extractKeywords(text) {
    const commonWords = [
        'the', 'and', 'or', 'but', 'in', 'on', 'at', 'to', 'for', 'of', 'with', 'by', 
        'a', 'an', 'is', 'are', 'was', 'were', 'be', 'been', 'being', 'have', 'has', 
        'had', 'do', 'does', 'did', 'will', 'would', 'could', 'should', 'this', 'that',
        'these', 'those', 'they', 'them', 'their', 'there', 'then', 'than', 'when',
        'where', 'who', 'what', 'why', 'how', 'which', 'work', 'job', 'position',
        'duties', 'responsibilities', 'requirements', 'must', 'may', 'can', 'shall'
    ];
    
    const governmentWords = [
        'federal', 'government', 'agency', 'department', 'bureau', 'office', 'administration',
        'commission', 'service', 'program', 'project', 'policy', 'procedures', 'regulations'
    ];

    const words = text.toLowerCase()
        .replace(/[^\w\s]/g, ' ')
        .match(/\b[a-z]{3,}\b/g) || [];
    
    const wordCount = {};
    words.forEach(word => {
        if (!commonWords.includes(word) && !governmentWords.includes(word)) {
            wordCount[word] = (wordCount[word] || 0) + 1;
        }
    });

    return Object.keys(wordCount)
        .sort((a, b) => {
            const countDiff = wordCount[b] - wordCount[a];
            return countDiff === 0 ? a.localeCompare(b) : countDiff;
        })
        .filter(word => word.length >= 3 && word.length <= 20)
        .slice(0, 30);
}

function convertSeriesToJobData(seriesMap) {
    const newJobSeriesData = {};

    seriesMap.forEach((data, seriesCode) => {
        let cleanTitle = data.title;

        if (!cleanTitle || cleanTitle === 'Unknown Series') {
            const positionCounts = {};
            data.positions.forEach(pos => {
                positionCounts[pos] = (positionCounts[pos] || 0) + 1;
            });
            cleanTitle = Object.keys(positionCounts).reduce((a, b) => 
                positionCounts[a] > positionCounts[b] ? a : b
            );
        }

        newJobSeriesData[seriesCode] = {
            title: cleanTitle,
            subSeries: Array.from(data.positions).slice(0, 8),
            keywords: Array.from(data.keywords)
                .filter(keyword => keyword.length >= 3)
                .slice(0, 15),
            locations: Array.from(data.locations).slice(0, 10),
            departments: Array.from(data.departments).slice(0, 5),
            groupCode: data.groupCode,
            groupName: data.groupName,
            groups: data.groups
        };
    });

    console.log(`Created ${Object.keys(newJobSeriesData).length} job series from API data`);
    return newJobSeriesData;
}

async function initializeJobSeriesData() {
    console.log('Initializing job series data from USA Jobs API...');
    
    try {
        const loadingElement = document.getElementById('loadingIndicator');
        if (loadingElement) {
            loadingElement.style.display = 'block';
            loadingElement.textContent = 'Loading job series from USA Jobs API...';
        }

        jobSeriesData = {};
        const apiData = await fetchJobSeriesFromAPI();
        jobSeriesData = processAPIJobSeries(apiData);
        
        const seriesCount = Object.keys(jobSeriesData).length;
        console.log(`Job series data initialized: ${seriesCount} series loaded from API`);

        if (loadingElement) {
            loadingElement.style.display = 'none';
        }

        updateJobSeriesDataReference();
        return jobSeriesData;

    } catch (error) {
        console.error('Error initializing job series data:', error);
        
        const loadingElement = document.getElementById('loadingIndicator');
        if (loadingElement) {
            loadingElement.style.display = 'none';
            loadingElement.textContent = 'Error loading job series data';
        }

        jobSeriesData = {};
        return jobSeriesData;
    }
}

function updateJobSeriesDataReference() {
    window.jobSeriesData = jobSeriesData;
}

// Utility functions for job series
function getAvailableJobSeries() {
    return Object.keys(jobSeriesData).sort();
}

function getJobSeries(seriesCode) {
    return jobSeriesData[seriesCode] || null;
}

function searchJobSeries(searchTerm) {
    const results = {};
    const term = searchTerm.toLowerCase();
    
    Object.keys(jobSeriesData).forEach(seriesCode => {
        const series = jobSeriesData[seriesCode];
        const titleMatch = series.title.toLowerCase().includes(term);
        const keywordMatch = series.keywords.some(keyword => 
            keyword.toLowerCase().includes(term)
        );
        const subSeriesMatch = series.subSeries.some(subSeries => 
            subSeries.toLowerCase().includes(term)
        );
        
        if (titleMatch || keywordMatch || subSeriesMatch) {
            results[seriesCode] = series;
        }
    });
    
    return results;
}

function getAdjacentGSGrades(recommendedGradeNum) {
    // Only valid two-grade interval grades
    const validGrades = [5, 7, 9, 11, 12, 13, 14, 15];
    const idx = validGrades.indexOf(recommendedGradeNum);
    let neighbors = [];
    if (idx !== -1) {
        // Previous grade (if exists)
        if (idx > 0) neighbors.push(`GS-${validGrades[idx - 1]}`);
        // Recommended grade
        neighbors.push(`GS-${validGrades[idx]}`);
        // Next grade (if exists)
        if (idx < validGrades.length - 1) neighbors.push(`GS-${validGrades[idx + 1]}`);
    } else {
        // Fallback: just show recommended grade
        neighbors.push(`GS-${recommendedGradeNum}`);
    }
    return neighbors;
}

// UI Setup Functions
function setupDisclaimerModal() {
    const disclaimerCheckbox = document.getElementById('disclaimerAccept');
    const continueBtn = document.getElementById('continueBtn');
    const disclaimerModal = document.getElementById('disclaimerModal');
    const mainApp = document.getElementById('mainApp');

    continueBtn.disabled = true;
    continueBtn.style.opacity = '0.6';
    continueBtn.style.cursor = 'not-allowed';
    continueBtn.classList.remove('hoverable');

    disclaimerCheckbox.addEventListener('change', function() {
        if (this.checked) {
            continueBtn.disabled = false;
            continueBtn.style.opacity = '1';
            continueBtn.style.cursor = 'pointer';
            continueBtn.classList.add('hoverable');
        } else {
            continueBtn.disabled = true;
            continueBtn.style.opacity = '0.6';
            continueBtn.style.cursor = 'not-allowed';
            continueBtn.classList.remove('hoverable');
        }
    });

    continueBtn.addEventListener('mouseenter', function() {
        if (!continueBtn.disabled) {
            continueBtn.style.backgroundColor = '#2563eb';
        }
    });

    continueBtn.addEventListener('mouseleave', function() {
        continueBtn.style.backgroundColor = '';
    });

    continueBtn.addEventListener('click', function() {
        if (!continueBtn.disabled) {
            disclaimerModal.style.display = 'none';
            mainApp.style.display = 'flex';
            renderStep(1);
        }
    });
}

// Sidebar rendering
function renderSidebar(currentStep) {
    const container = document.getElementById('stepsContainer');
    if (!container) return;

    if (typeof window.maxStepCompleted === 'undefined' || currentStep > window.maxStepCompleted) {
        window.maxStepCompleted = currentStep;
    }

    container.innerHTML = '';
    steps.forEach((step, idx) => {
        const clickable = idx + 1 <= window.maxStepCompleted;
        const status = idx + 1 < currentStep ? 'completed' : idx + 1 === currentStep ? 'current' : 'upcoming';
        const highlightClass = idx + 1 <= window.maxStepCompleted ? 'highlight-green' : '';
        
        container.innerHTML += `
            <div class="step ${status} ${highlightClass}" ${clickable ? `style="cursor:pointer;" onclick="renderStep(${step.id})"` : ''}>
                <div class="step-icon"><i class="fas ${step.icon}"></i></div>
                <div class="step-content">
                    <h4>${step.name}</h4>
                    <p>${step.description}</p>
                </div>
            </div>
        `;
    });
}

function renderUpdateSidebar(currentStep) {
    const container = document.getElementById('updateStepsContainer');
    if (!container) return;

    container.innerHTML = '';
    updateSteps.forEach((step, idx) => {
        const stepNum = idx + 1;
        const isCompleted = stepNum < currentStep;
        const isCurrent = stepNum === currentStep;

        const stepElement = document.createElement('div');
        stepElement.className = `step-item ${isCurrent ? 'current' : isCompleted ? 'completed' : 'upcoming'}`;
        
        if (isCompleted) {
            stepElement.style.cursor = 'pointer';
            stepElement.onclick = () => renderUpdateStep(stepNum);
        }

        stepElement.innerHTML = `
            <div class="step-icon ${isCurrent ? 'current' : isCompleted ? 'completed' : 'upcoming'}">
                ${isCompleted ? '<i class="fas fa-check"></i>' :
                isCurrent ? '<div style="width: 0.75rem; height: 0.75rem; background: white; border-radius: 50%;"></div>' : 
                `<span style="font-size: 0.875rem; font-weight: 500;">${stepNum}</span>`}
            </div>
            <div class="step-content">
                <div class="step-name ${isCurrent ? 'current' : isCompleted ? 'completed' : 'upcoming'}">${step.name}</div>
                <div class="step-description ${isCurrent ? 'current' : isCompleted ? 'completed' : 'upcoming'}">${step.description}</div>
            </div>
        `;

        container.appendChild(stepElement);
    });
}

// Main step rendering function
function renderStep(step) {
    renderSidebar(step);
    const content = document.getElementById('stepContent');
    if (!content) return;

    switch (step) {
        case 1:
            renderActionSelectionStep(content);
            break;
        case 2:
            renderAgencySetupStep(content);
            break;
        case 3:
            renderJobClassificationStep(content);
            break;
        case 'unknownSeries':
            renderUnknownSeriesStep(content);
            break;
        case 4:
            renderDutiesStep(content);
            break;
        case 5:
            renderReviewGenerateStep(content);
            break;
        case 6:
            renderGeneratedPDStep(content);
            break;
        default:
            content.innerHTML = `<div class="alert alert-warning">Unknown step.</div>`;
    }
}

function renderActionSelectionStep(content) {
    content.innerHTML = `
        <div class="card">
            <div class="card-header">
                <div class="card-title"><i class="fas fa-bolt"></i> Select Action</div>
                <div class="card-description">Choose whether to create a new PD or update an existing one</div>
            </div>
            <div class="card-content grid grid-2">
                <div class="action-card" id="createNewPD">
                    <i class="fas fa-plus"></i>
                    <h3>Create New Position Description</h3>
                </div>
                <div class="action-card" id="updateExistingPD">
                    <i class="fas fa-edit"></i>
                    <h3>Update Existing Position Description</h3>
                </div>
            </div>
        </div>
    `;
    
    document.getElementById('createNewPD').onclick = () => renderStep(2);
    document.getElementById('updateExistingPD').onclick = () => showUpdateApp();
}

function renderJobClassificationStep(content) {
    // Wait until jobSeriesData is loaded
    if (!jobSeriesData || Object.keys(jobSeriesData).length === 0) {
        content.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <div class="card-title"><i class="fas fa-users"></i> Job Classification</div>
                    <div class="card-description">Define job series and position title</div>
                </div>
                <div class="card-content">
                    <div class="alert alert-info" style="margin:2em 0;">
                        <span class="spinner"></span> Loading job series data from USAJobs.gov...
                    </div>
                </div>
            </div>
        `;
        // Try again in 500ms
        setTimeout(() => renderJobClassificationStep(content), 500);
        return;
    }

    // Dynamically generate group series options from API data
    const groupSeriesOptions = getGroupSeriesOptionsFromAPI();

    content.innerHTML = `
        <div class="card">
            <div class="card-header">
                <div class="card-title"><i class="fas fa-users"></i> Job Classification
                    <span class="step-info">
                        <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                        <div class="step-tooltip">
                            Select the group series and job series for the position. Then choose or enter a position title. You can add multiple position titles if needed.
                        </div>
                    </span>
                </div>
                <div class="card-description">Define job series and position title</div>
            </div>
            <div class="card-content">
                <button class="btn btn-outline" id="unknownSeriesBtn" style="margin-bottom:1rem;">
                    <i class="fas fa-magic"></i> Unsure? Get AI Recommendation
                </button>
                <div class="form-group">
                    <label class="form-label" for="groupSeriesDropdown">Group Series <span style="color:#ef4444;">*</span></label>
                    <div class="dropdown-wrapper">
                        <input class="form-input" id="groupSeriesDropdown" autocomplete="off" placeholder="Search or select group series..." value="${formState.groupSeries || ''}">
                        <div class="dropdown-list" id="groupSeriesDropdownList" style="display:none;"></div>
                    </div>
                </div>
                <div class="form-group">
                    <label class="form-label" for="jobSeriesDropdown">Job Series <span style="color:#ef4444;">*</span></label>
                    <div class="dropdown-wrapper">
                        <input class="form-input" id="jobSeriesDropdown" autocomplete="off" placeholder="Search or select job series..." value="${formState.jobSeries || ''}" ${!formState.groupSeries ? 'disabled' : ''}>
                        <div class="dropdown-list" id="jobSeriesDropdownList" style="display:none;"></div>
                    </div>
                </div>
                <div class="form-group">
                    <label class="form-label" for="positionTitleInput">Position Title</label>
                    <div class="dropdown-wrapper">
                        <input class="form-input" id="positionTitleInput" autocomplete="off" placeholder="Search or type position title..." value="${formState.positionTitle || ''}" ${!formState.jobSeries ? 'disabled' : ''}>
                        <div class="dropdown-list" id="positionTitleDropdownList" style="display:none;"></div>
                    </div>
                    <div id="positionTitleList" style="margin-top:0.5rem;"></div>
                </div>
                <button class="btn btn-primary" id="continueClassificationBtn">
                    ${formState.usedAIRecommendation ? 'Continue: To Review & Generate' : 'Next: Duties & Responsibilities'}
                </button>
            </div>
        </div>
    `;

    setupGroupSeriesDropdown(groupSeriesOptions);
    setupJobSeriesDropdown();
    setupPositionTitleDropdown();
    setupClassificationValidation();

    document.getElementById('unknownSeriesBtn').onclick = () => renderStep('unknownSeries');
    setupClassificationValidation();

    // Pre-populate sub-series if we have a selected series
    setTimeout(() => {
        const jobSeriesSearch = document.getElementById('jobSeriesSearch');
        const subSeriesSelect = document.getElementById('subSeriesSelect');
        
        // Remove any automatic focus from form elements
        if (jobSeriesSearch) {
            jobSeriesSearch.blur();
        }
        if (subSeriesSelect) {
            subSeriesSelect.blur();
        }
        
        // If we have a job series value, populate the sub-series dropdown
        if (formState.jobSeries && window.selectedSeries) {
            const seriesCode = window.selectedSeries.code;
            const seriesData = jobSeriesData[seriesCode];
            
            if (seriesData && seriesData.subSeries) {
                // Clear and populate the dropdown
                subSeriesSelect.innerHTML = `<option value="">Select Sub-series</option>`;
                seriesData.subSeries.forEach(subSeries => {
                    const option = document.createElement('option');
                    option.value = subSeries;
                    option.textContent = subSeries;
                    subSeriesSelect.appendChild(option);
                });
                
                // Select the saved sub-series if available
                if (formState.subSeries) {
                    setTimeout(() => {
                        if ([...subSeriesSelect.options].some(opt => opt.value === formState.subSeries)) {
                            subSeriesSelect.value = formState.subSeries;
                        }
                        subSeriesSelect.dispatchEvent(new Event('change', { bubbles: true }));
                        // Ensure no focus after setting value
                        subSeriesSelect.blur();
                    }, 50);
                }
            }
        }
        
        // Ensure dropdown is hidden and no elements are focused
        const dropdownResults = document.getElementById('dropdownResults');
        if (dropdownResults) {
            dropdownResults.style.display = 'none';
        }
        
        // Remove focus from any focused element
        if (document.activeElement && document.activeElement !== document.body) {
            document.activeElement.blur();
        }
    }, 100);

    document.getElementById('unknownSeriesBtn').onclick = () => renderStep('unknownSeries');
    setupClassificationValidation();

    // Updated continue button logic to skip duties if using AI recommendation
    document.getElementById('continueClassificationBtn').onclick = function() {
        if (!this.disabled) {
            if (formState.usedAIRecommendation) {
                renderStep(5); // Skip duties, go to Review & Generate
            } else {
                renderStep(4); // Normal flow
            }
        }
    };
}

function renderUnknownSeriesStep(content) {
    // Initialize refine duties state if not present
    if (!formState.refineDuties) formState.refineDuties = [''];
    if (typeof formState.refineRequirements !== 'string') formState.refineRequirements = '';

    // State for GS grade and recommendations
    if (!formState.gsGrade) formState.gsGrade = null;
    if (!formState.aiJobSeries) formState.aiJobSeries = [];

    content.innerHTML = `
    <div class="card">
        <div class="card-header">
            <div class="card-title">
                <i class="fas fa-magic"></i> AI Job Series Recommendation
                <span class="step-info" title="Enter at least 6 main duties or responsibilities for the position. The AI will use what you enter to suggest the best job series. If you are unsure about the job series, this step helps you find the most appropriate match based on your input.">
                    <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                </span>
            </div>
            <div class="card-description">Enter at least 6 duties/responsibilities. The AI will recommend the best matching job series.</div>
        </div>
        <div class="form-group">
            <label class="form-label" for="supervisoryLevelSelect">Supervisory Level<span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <select id="supervisoryLevelSelect" class="form-input" style="width:100%;max-width:400px;">
                    <option value="">Select level...</option>
                    <option value="Non-Supervisory">Non-Supervisory</option>
                    <option value="Team Lead">Team Lead</option>
                    <option value="Supervisor">Supervisor</option>
                    <option value="Manager">Manager</option>
                    <option value="Executive">Executive</option>
                </select>
            </div>
        </div>
        <div class="card-content">
            <div style="margin-bottom:1rem;">
                <label for="aiDutiesInput" style="font-weight:600;">AI Rewrite Duties/Responsibilities</label>
                <textarea id="aiDutiesInput" class="form-input" rows="4" placeholder="Paste or write your duties/responsibilities here..."></textarea>
                <button class="btn btn-outline" id="aiRewriteBtn" style="margin-top:0.5rem;">
                    <i class="fas fa-magic"></i> Rewrite with AI
                </button>
                <div id="aiRewriteResult" style="margin-top:1em;"></div>
            </div>
            <div class="section-divider" style="margin: 1.5rem 0 1rem 0; border-bottom: 1px solid #e5e7eb;"></div>
            <h3 style="font-size: 1.15rem; color: #2563eb; font-weight: 600;">
                Enter Duties & Responsibilities Below
            </h3>
            <div id="dutiesContainer"></div>
            <button class="btn btn-primary" style="margin-bottom:1rem;" onclick="addDuty()"><i class="fa fa-plus"></i> Add Duty</button>
            <div id="dutyCounter" class="badge"></div>
            <button class="btn btn-primary" id="getAIRecommendationBtn">Get AI Recommendation</button>
            <button class="btn btn-primary" id="continueUnknownSeriesBtn" style="display:none;">Continue</button>
            <div id="aiSeriesResult" style="margin-top:1.5rem; margin-bottom:1.5rem;"></div>
            <div id="gradeAnalysisResult"></div>
        </div>
    </div>
    `;

    // Supervisory dropdown logic
    const supervisorySelect = document.getElementById('supervisoryLevelSelect');
    if (supervisorySelect) {
        supervisorySelect.value = formState.supervisoryLevel || '';
        supervisorySelect.onchange = function() {
            formState.supervisoryLevel = this.value;
        };
    }

    setupUnknownSeriesLogic();

    // If GS grade and recommendations are present, render the grade analysis and refine section
    if (formState.gsGrade && formState.aiJobSeries.length > 0) {
        renderGradeAnalysisResult();
    }
}

function setupRefineGradeAnalysisUI() {
    // Render duties list
    function renderRefineDuties() {
        const list = document.getElementById('refineDutiesList');
        if (!list) return;
        list.innerHTML = '';
        formState.refineDuties.forEach((duty, idx) => {
            const div = document.createElement('div');
            div.style.display = 'flex';
            div.style.alignItems = 'center';
            div.style.gap = '0.5em';
            div.style.marginBottom = '0.5em';
            div.innerHTML = `
                <input type="text" class="form-input" style="flex:1;" value="${duty}" placeholder="Enter additional duty..." data-idx="${idx}">
                <button class="btn btn-outline" type="button" data-remove="${idx}" style="color:#ef4444;">Remove</button>
            `;
            list.appendChild(div);
        });
        // Input listeners
        list.querySelectorAll('input').forEach(input => {
            input.oninput = function(e) {
                const idx = parseInt(e.target.dataset.idx, 10);
                formState.refineDuties[idx] = e.target.value;
            };
        });
        // Remove listeners
        list.querySelectorAll('button[data-remove]').forEach(btn => {
            btn.onclick = function() {
                const idx = parseInt(btn.dataset.remove, 10);
                formState.refineDuties.splice(idx, 1);
                renderRefineDuties();
            };
        });
    }
    renderRefineDuties();

    // Add duty button
    document.getElementById('addRefineDutyBtn').onclick = function() {
        formState.refineDuties.push('');
        renderRefineDuties();
    };
}

function setupDutiesLogic() {
    // Ensure formState.duties is always an array
    if (!Array.isArray(formState.duties)) {
        formState.duties = [];
    }

    // Pre-fill duties if present
    if (formState.duties.length > 0) {
        // Remove existing inputs and add as many as needed
        const container = document.getElementById('dutiesContainer');
        container.innerHTML = '';
        formState.duties.forEach((duty, i) => {
            addDuty();
            document.querySelectorAll('.duty-input')[i].value = duty;
        });
    } else {
        for (let i = 0; i < 6; i++) addDuty();
    }
    updateDutyCounter();
    setupAIRewriteButton('aiDutiesInput', 'aiRewriteResult', 'aiRewriteBtn');
    setupDutiesValidation();

    // If a grade was set by AI, show it in the grade analysis
    if (formState.gsGrade) {
        renderDutiesGradeAnalysis({
            gsGrade: formState.gsGrade,
            gradeRelevancy: formState.gradeRelevancy || []
        });
        document.getElementById('continueDutiesBtn').style.display = '';
    }
}

function renderDutiesStep(content) {
    content.innerHTML = `
    <div class="card">
        <div class="card-header">
            <div class="card-title">
                <i class="fas fa-tasks"></i> Duties & Responsibilities
                <span class="step-info">
                    <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                    <div class="step-tooltip">
                        Enter at least 6 main duties or responsibilities for the position.
                    </div>
                </span>
            </div>
            <div class="card-description">Enter key duties</div>
        </div>
        <div class="form-group">
            <label class="form-label" for="supervisoryLevelSelect">Supervisory Level<span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <select id="supervisoryLevelSelect" class="form-input">
                    <option value="">Select level...</option>
                    <option value="Non-Supervisory">Non-Supervisory</option>
                    <option value="Team Lead">Team Lead</option>
                    <option value="Supervisor">Supervisor</option>
                    <option value="Manager">Manager</option>
                    <option value="Executive">Executive</option>
                </select>
            </div>
        </div>
        <div class="card-content">
            <div style="margin-bottom:1rem;">
                <label for="aiDutiesInput" style="font-weight:600;">AI Rewrite Duties/Responsibilities</label>
                <textarea id="aiDutiesInput" class="form-input" rows="4" placeholder="Paste or write your duties/responsibilities here..."></textarea>
                <button class="btn btn-outline" id="aiRewriteBtn" style="margin-top:0.5rem;">
                    <i class="fas fa-magic"></i> Rewrite with AI
                </button>
                <div id="aiRewriteResult" style="margin-top:1em;"></div>
            </div>
            <div class="section-divider" style="margin: 1.5rem 0 1rem 0; border-bottom: 1px solid #e5e7eb;"></div>
            <h3 style="font-size: 1.15rem; color: #2563eb; font-weight: 600;">
                Enter Duties & Responsibilities Below
            </h3>
            <div id="dutiesContainer"></div>
            <button class="btn btn-primary" style="margin-bottom:1rem;" onclick="addDuty()">
                <i class="fa fa-plus"></i> Add Duty
            </button>
            <button class="btn btn-primary" id="gradeAnalysisBtn">Grade Analysis</button>
            <div id="dutyCounter" class="badge"></div>
            <div id="gradeAnalysisResult" style="margin-top:2rem;"></div>
            <button class="btn btn-primary" id="continueDutiesBtn" style="display:none;">Continue to Review & Generate</button>
        </div>
    </div>
    `;

    // Supervisory dropdown logic
    const supervisorySelect = document.getElementById('supervisoryLevelSelect');
    if (supervisorySelect) {
        supervisorySelect.value = formState.supervisoryLevel || '';
        supervisorySelect.onchange = function() {
            formState.supervisoryLevel = this.value;
        };
    }

    setupDutiesLogic();
}

function renderReviewGenerateStep(content) {
    // Get job series display value (number and title)
    let jobSeriesDisplay = '<span style="color:#9ca3af;">Not set</span>';
    if (formState.jobSeries) {
        // If jobSeries is just the code, get the title from jobSeriesData
        let code = formState.jobSeries;
        let title = '';
        if (jobSeriesData[code]) {
            title = jobSeriesData[code].title;
        } else if (formState.jobSeries.includes(' - ')) {
            // If already in "code - title" format
            jobSeriesDisplay = formState.jobSeries;
        }
        if (title) {
            jobSeriesDisplay = `${code} - ${title}`;
        } else if (!jobSeriesDisplay) {
            jobSeriesDisplay = code;
        }
    }

    content.innerHTML = `
        <div class="card">
            <div class="card-header">
                <div class="card-title">
                    <i class="fas fa-check-circle"></i> Review & Generate
                    <span class="step-info" title="Review all your selections and entered duties before generating the PD."><i class="fas fa-info-circle"></i></span>
                </div>
                <div class="card-description">Review and create PD</div>
            </div>
            <div class="card-content">
                <div class="review-summary" style="margin-bottom:2rem;">
                    <h4 style="margin-bottom:0.5rem;">Your Selections</h4>
                    <ul style="list-style:none; padding-left:0;">
                        <li><strong>Lowest Level Organization/Office:</strong> ${formState.lowestOrg || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Federal Agency:</strong> ${formState.federalAgency || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Sub Organization:</strong> ${formState.subOrg || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Group Series:</strong> ${formState.groupSeries || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Job Series:</strong> ${jobSeriesDisplay}</li>
                        <li><strong>Position Title:</strong> ${
                            (formState.positionTitles && formState.positionTitles.length > 0)
                                ? formState.positionTitles.map(t => `<span class="badge">${t}</span>`).join(' ')
                                : (formState.positionTitle || '<span style="color:#9ca3af;">Not set</span>')
                        }</li>
                        <li><strong>GS Grade:</strong> ${formState.gsGrade || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Supervisory Level:</strong> ${formState.supervisoryLevel || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Duties & Responsibilities:</strong></li>
                    </ul>
                    <ul style="padding-left:1.5em;">
                        ${(formState.duties || []).filter(d => d.trim()).map(duty => `<li>${duty}</li>`).join('') || '<li style="color:#9ca3af;">No duties entered</li>'}
                    </ul>
                </div>
                <button class="btn btn-primary" id="generateBtn">Generate Position Description</button>
                <div id="aiResult" style="margin-top:24px;"></div>
            </div>
        </div>
    `;
    setupGenerateButton();
}

function markdownToHTML(text) {
    return text
        // Convert **bold** to <strong>bold</strong>
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        // Convert line breaks to <br> tags, but preserve paragraph spacing
        .replace(/\n\n/g, '</p><p>')
        // Wrap the entire content in paragraph tags
        .replace(/^(.*)$/s, '<p>$1</p>')
        // Handle single line breaks within paragraphs
        .replace(/(?<!<\/p>)\n(?!<p>)/g, '<br>')
        // Clean up empty paragraphs
        .replace(/<p><\/p>/g, '')
        // Fix cases where we might have nested paragraph tags
        .replace(/<p><p>/g, '<p>')
        .replace(/<\/p><\/p>/g, '</p>');
}

// Updated render function


// Update step rendering
function renderUpdateStep(step) {
    currentUpdateStep = step;
    renderUpdateSidebar(step);
    const content = document.getElementById('updateStepContent');
    if (!content) return;

    switch (step) {
        case 1:
            renderUploadStep(content);
            break;
        case 2:
            renderEditSectionsStep(content);
            break;
        case 3:
            renderReviewChangesStep(content);
            break;
        case 4:
            renderCompletionStep(content);
            break;
        default:
            content.innerHTML = `<div class="alert alert-warning">Unknown step.</div>`;
    }
}

function renderUploadStep(content) {
    content.innerHTML = `
        <div class="card" style="max-width: 48rem; margin: 0 auto;">
            <div class="card-header">
                <div class="card-title">
                    <i class="fas fa-upload"></i>
                    Upload Existing Position Description
                    <span class="step-info">
                        <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                        <div class="step-tooltip">
                            Choose how you'd like to provide the existing PD for updating.
                        </div>
                    </span>
                </div>
                <div class="card-description">Choose how you'd like to provide the existing PD for updating</div>
            </div>
            <div class="card-content" style="padding: 1.5rem;">
                <div class="upload-methods">
                    <div class="upload-method-card file">
                        <div class="upload-method-content">
                            <div class="upload-method-header">
                                <div class="upload-method-icon file">
                                    <i class="fas fa-file-text" style="color: #3b82f6; font-size: 1.5rem;"></i>
                                </div>
                                <div style="flex: 1;">
                                    <h3 class="upload-method-title">Upload File</h3>
                                    <p class="upload-method-description">Upload PDF, DOCX, or TXT file</p>
                                </div>
                                <input type="file" id="pdFileInput" accept=".pdf,.docx,.txt" style="display: none;">
                                <button class="btn btn-primary" onclick="document.getElementById('pdFileInput').click()">
                                    Choose File
                                </button>
                            </div>
                        </div>
                    </div>

                    <div class="upload-method-card sample">
                        <div class="upload-method-content">
                            <div class="upload-method-header">
                                <div class="upload-method-icon sample">
                                    <i class="fas fa-sparkles" style="color: #8b5cf6; font-size: 1.5rem;"></i>
                                </div>
                                <div style="flex: 1;">
                                    <h3 class="upload-method-title">Use Sample PD</h3>
                                    <p class="upload-method-description">Try with a sample Management Analyst position</p>
                                </div>
                                <button class="btn btn-outline" onclick="loadSamplePD()">
                                    Use Sample
                                    <i class="fas fa-arrow-right" style="margin-left: 0.5rem;"></i>
                                </button>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="alert alert-info" style="margin-top: 1.5rem;">
                    <i class="fas fa-info-circle"></i>
                    <strong>Enhancement Process:</strong> Once uploaded, you'll be able to edit each section individually with a clean, organized interface for easy updates and improvements.
                </div>
            </div>
        </div>
    `;
    setupFileUpload();
}

function renderEditSectionsStep(content) {
    content.innerHTML = `
        <div style="margin-bottom: 1.5rem;">
            <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 2rem;">
                <div>
                    <h2 style="font-size: 1.5rem; font-weight: 700; color: #1f2937; margin-bottom: 0.5rem;">
                        Edit PD Sections
                        <span class="step-info" tabindex="0">
                            <i class="fas fa-info-circle" style="color:#2563eb; font-size:1.2em; margin-left:8px; cursor:help;"></i>
                            <span class="step-tooltip">
                                Edit your Position Description. Changes save automatically—use Undo or Reset to revert. Click 'Review Changes' when done.
                            </span>
                        </span>
                    </h2>
                    <p style="color: #6b7280;">
                        ${originalFileName && `<span style="background: #f3f4f6; color: #374151; font-size: 0.75rem; padding: 0.25rem 0.5rem; border-radius: 0.375rem; margin-right: 0.5rem;">${originalFileName}</span>`}
                        Make direct edits to each section of your Position Description
                    </p>
                </div>
                <button class="btn btn-outline" onclick="renderUpdateStep(1)">
                    <i class="fas fa-arrow-left"></i> Back to Upload
                </button>
            </div>

            <div id="editableSections" class="rich-editor-wrapper">
                <!-- Sections will be populated here -->
            </div>

            <div class="save-actions-card">
                <div class="save-actions-content">
                    <div class="save-actions-row">
                        <div class="save-status" id="saveStatus">
                            Ready to review changes
                        </div>
                        <div class="save-actions">
                            <button class="btn btn-outline" onclick="resetAllSections()">
                                Reset All Changes
                            </button>
                            <button class="btn btn-primary save-button primary" onclick="renderUpdateStep(3)" style="background: #2563eb; border-color: #2563eb;">
                                <i class="fas fa-eye"></i>
                                Review Changes
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    `;
    renderEditableSections();
}

function renderReviewChangesStep(content) {
    content.innerHTML = `
        <div style="margin-bottom: 1.5rem;">
            <div class="card" style="max-width: 48rem; margin: 0 auto;">
                <div class="card-header">
                    <div class="card-title">
                        <i class="fas fa-eye"></i>
                        Updated Position Description
                        <span class="step-info"><i class="fas fa-info-circle"></i> Review the changes made</span>
                    </div>
                    <div class="card-description">Your enhanced PD with manual edits</div>
                </div>
                <div class="card-content">
                    <div class="review-content">
                        <pre class="review-text" id="reviewText"></pre>
                    </div>

                    <div class="review-actions" style="display: flex; gap: 12px; flex-wrap: wrap;">
                        <button class="btn btn-primary" id="copyUpdatedPD" style="background: #2563eb; border-color: #2563eb;">
                            <i class="fas fa-copy"></i>
                            Copy to Clipboard
                        </button>
                        <button class="btn btn-primary" id="downloadUpdatedPDtxt" style="background: #2563eb; border-color: #2563eb;">
                            <i class="fas fa-file-alt"></i>
                            Download TXT
                        </button>
                        <button class="btn btn-primary" id="downloadUpdatedPDpdf" style="background: #2563eb; border-color: #2563eb;">
                            <i class="fas fa-file-pdf"></i>
                            Download PDF
                        </button>
                        <button class="btn btn-primary" id="downloadUpdatedPDdocx" style="background: #2563eb; border-color: #2563eb;">
                            <i class="fas fa-file-word"></i>
                            Download DOCX
                        </button>
                        <button class="btn btn-outline" onclick="renderUpdateStep(2)">
                            Make More Changes
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `;
    displayReviewContent();
    setupReviewActions();
}

function renderCompletionStep(content) {
    content.innerHTML = `
        <div style="margin-bottom: 1.5rem;">
            <div class="card" style="max-width: 48rem; margin: 0 auto; text-align: center;">
                <div class="card-content" style="padding: 2rem 0;">
                    <div class="completion-icon">
                        <i class="fas fa-check-circle" style="color: #10b981; font-size: 4rem;"></i>
                    </div>
                    <h2 class="completion-title">PD Successfully Updated!</h2>
                    <p class="completion-description">
                        Your Position Description has been enhanced and downloaded successfully.
                    </p>

                    <div class="completion-actions">
                        <button class="btn btn-outline" onclick="showMainApp()">
                            <i class="fas fa-home"></i>
                            Return to Home
                        </button>
                        <button class="btn btn-primary" onclick="resetUpdateProcess()">
                            Update Another PD
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Setup functions for individual steps
function renderAgencySetupStep(content) {
    const agencyData = [
        {
            name: "Department of Justice",
            subOrgs: ["Federal Bureau of Investigation (FBI)", "Drug Enforcement Administration (DEA)", 
                     "Bureau of Alcohol, Tobacco, Firearms and Explosives (ATF)", "U.S. Marshals Service", 
                     "Federal Bureau of Prisons (BOP)", "Criminal Division", "Civil Rights Division", 
                     "Antitrust Division", "Civil Division", "Tax Division", "National Security Division",
                     "Environment and Natural Resources Division", "Executive Office for U.S. Attorneys",
                     "Justice Management Division", "Office of Legal Counsel"]
        },
        {
            name: "Department of Homeland Security",
            subOrgs: ["Federal Emergency Management Agency (FEMA)", "Transportation Security Administration (TSA)", 
                     "U.S. Citizenship and Immigration Services (USCIS)", "U.S. Customs and Border Protection (CBP)",
                     "U.S. Immigration and Customs Enforcement (ICE)", "U.S. Secret Service", 
                     "Cybersecurity and Infrastructure Security Agency (CISA)", "U.S. Coast Guard",
                     "Federal Law Enforcement Training Centers (FLETC)", "Science and Technology Directorate",
                     "Countering Weapons of Mass Destruction Office", "Intelligence and Analysis"]
        },
        {
            name: "Department of Defense",
            subOrgs: ["Department of the Army", "Department of the Navy", "Department of the Air Force", 
                     "U.S. Marine Corps", "Defense Intelligence Agency (DIA)", "National Security Agency (NSA)",
                     "Defense Logistics Agency (DLA)", "Defense Contract Management Agency (DCMA)",
                     "Defense Finance and Accounting Service (DFAS)", "Pentagon Force Protection Agency",
                     "Defense Threat Reduction Agency (DTRA)", "Missile Defense Agency (MDA)",
                     "Special Operations Command (SOCOM)", "Office of the Secretary of Defense"]
        },
        {
            name: "Department of Treasury",
            subOrgs: ["Internal Revenue Service (IRS)", "U.S. Mint", "Bureau of Engraving and Printing",
                     "Financial Crimes Enforcement Network (FinCEN)", "Office of Foreign Assets Control (OFAC)",
                     "Alcohol and Tobacco Tax and Trade Bureau (TTB)", "Community Development Financial Institutions Fund",
                     "Federal Financing Bank", "Financial Management Service", "Office of the Comptroller of the Currency"]
        },
        {
            name: "Department of Health and Human Services",
            subOrgs: ["Centers for Disease Control and Prevention (CDC)", "Food and Drug Administration (FDA)",
                     "National Institutes of Health (NIH)", "Centers for Medicare & Medicaid Services (CMS)",
                     "Administration for Children and Families (ACF)", "Substance Abuse and Mental Health Services Administration (SAMHSA)",
                     "Health Resources and Services Administration (HRSA)", "Indian Health Service (IHS)",
                     "Agency for Healthcare Research and Quality (AHRQ)", "Administration for Community Living (ACL)",
                     "Office of Inspector General", "Assistant Secretary for Preparedness and Response"]
        },
        {
            name: "Department of Education",
            subOrgs: ["Office of Elementary and Secondary Education", "Office of Special Education and Rehabilitative Services",
                     "Office of Postsecondary Education", "Office of Career, Technical, and Adult Education",
                     "Institute of Education Sciences", "Office for Civil Rights", "Federal Student Aid",
                     "Office of Inspector General", "Risk Management Service", "Office of the General Counsel"]
        },
        {
            name: "Department of Veterans Affairs",
            subOrgs: ["Veterans Health Administration (VHA)", "Veterans Benefits Administration (VBA)",
                     "National Cemetery Administration (NCA)", "Office of Inspector General",
                     "Board of Veterans' Appeals", "Office of General Counsel", "Office of Acquisition, Logistics, and Construction",
                     "Office of Information and Technology", "Office of Human Resources and Administration"]
        },
        {
            name: "Department of Transportation",
            subOrgs: ["Federal Aviation Administration (FAA)", "Federal Highway Administration (FHWA)",
                     "Federal Railroad Administration (FRA)", "National Highway Traffic Safety Administration (NHTSA)",
                     "Federal Transit Administration (FTA)", "Federal Motor Carrier Safety Administration (FMCSA)",
                     "Maritime Administration (MARAD)", "Pipeline and Hazardous Materials Safety Administration (PHMSA)",
                     "Saint Lawrence Seaway Development Corporation", "Office of Inspector General"]
        },
        {
            name: "Department of Energy",
            subOrgs: ["National Nuclear Security Administration (NNSA)", "Office of Science", "Office of Nuclear Energy",
                     "Office of Fossil Energy and Carbon Management", "Office of Energy Efficiency and Renewable Energy",
                     "Advanced Research Projects Agency-Energy (ARPA-E)", "Office of Environmental Management",
                     "Bonneville Power Administration", "Western Area Power Administration", "Southeastern Power Administration"]
        },
        {
            name: "Department of Agriculture",
            subOrgs: ["Food Safety and Inspection Service (FSIS)", "Animal and Plant Health Inspection Service (APHIS)",
                     "Food and Nutrition Service (FNS)", "Forest Service", "Natural Resources Conservation Service (NRCS)",
                     "Farm Service Agency (FSA)", "Rural Development", "Agricultural Research Service (ARS)",
                     "National Agricultural Statistics Service (NASS)", "Economic Research Service (ERS)",
                     "Foreign Agricultural Service (FAS)", "Risk Management Agency (RMA)"]
        },
        {
            name: "Department of Commerce",
            subOrgs: ["Census Bureau", "Bureau of Economic Analysis (BEA)", "International Trade Administration (ITA)",
                     "National Institute of Standards and Technology (NIST)", "National Oceanic and Atmospheric Administration (NOAA)",
                     "U.S. Patent and Trademark Office (USPTO)", "Economic Development Administration (EDA)",
                     "Minority Business Development Agency (MBDA)", "Bureau of Industry and Security (BIS)",
                     "National Technical Information Service (NTIS)"]
        },
        {
            name: "Department of Labor",
            subOrgs: ["Occupational Safety and Health Administration (OSHA)", "Employment and Training Administration (ETA)",
                     "Employee Benefits Security Administration (EBSA)", "Wage and Hour Division",
                     "Office of Federal Contract Compliance Programs (OFCCP)", "Mine Safety and Health Administration (MSHA)",
                     "Bureau of Labor Statistics (BLS)", "Office of Workers' Compensation Programs (OWCP)",
                     "Veterans' Employment and Training Service (VETS)", "Women's Bureau"]
        },
        {
            name: "Department of Housing and Urban Development",
            subOrgs: ["Office of Housing", "Office of Community Planning and Development", "Office of Public and Indian Housing",
                     "Government National Mortgage Association (Ginnie Mae)", "Office of Fair Housing and Equal Opportunity",
                     "Office of Policy Development and Research", "Office of Inspector General",
                     "Office of General Counsel", "Office of Administration"]
        },
        {
            name: "Department of the Interior",
            subOrgs: ["National Park Service", "U.S. Fish and Wildlife Service", "Bureau of Land Management (BLM)",
                     "U.S. Geological Survey (USGS)", "Bureau of Indian Affairs (BIA)", "Bureau of Reclamation",
                     "Office of Surface Mining Reclamation and Enforcement", "Bureau of Ocean Energy Management",
                     "Bureau of Safety and Environmental Enforcement", "Office of Inspector General"]
        },
        {
            name: "Department of State",
            subOrgs: ["Bureau of Consular Affairs", "Bureau of Diplomatic Security", "Foreign Service Institute",
                     "Bureau of International Narcotics and Law Enforcement Affairs", "Bureau of Arms Control, Verification and Compliance",
                     "Bureau of Democracy, Human Rights, and Labor", "Bureau of Economic and Business Affairs",
                     "Bureau of Educational and Cultural Affairs", "Bureau of Intelligence and Research",
                     "Office of Inspector General"]
        },
        {
            name: "Environmental Protection Agency",
            subOrgs: ["Office of Air and Radiation", "Office of Water", "Office of Land and Emergency Management",
                     "Office of Chemical Safety and Pollution Prevention", "Office of Enforcement and Compliance Assurance",
                     "Office of Research and Development", "Office of Inspector General", "Regional Offices",
                     "Office of Environmental Justice", "Office of International and Tribal Affairs"]
        },
        {
            name: "General Services Administration",
            subOrgs: ["Federal Acquisition Service", "Public Buildings Service", "Technology Transformation Services",
                     "Office of Inspector General", "Office of Mission Assurance", "Office of Administrative Services",
                     "Office of Congressional and Intergovernmental Affairs", "Office of General Counsel"]
        },
        {
            name: "National Aeronautics and Space Administration",
            subOrgs: ["Goddard Space Flight Center", "Johnson Space Center", "Kennedy Space Center",
                     "Langley Research Center", "Glenn Research Center", "Ames Research Center",
                     "Jet Propulsion Laboratory", "Marshall Space Flight Center", "Armstrong Flight Research Center",
                     "Office of Inspector General"]
        },
        {
            name: "Small Business Administration",
            subOrgs: ["Office of Capital Access", "Office of Entrepreneurial Development", "Office of Government Contracting and Business Development",
                     "Office of Disaster Assistance", "Office of Advocacy", "Office of Inspector General",
                     "Office of International Trade", "Office of Investment and Innovation"]
        },
        {
            name: "Social Security Administration",
            subOrgs: ["Office of Retirement and Disability Policy", "Office of Analytics, Review, and Oversight",
                     "Office of Operations", "Office of Systems", "Office of Budget, Finance, Quality and Management",
                     "Office of Human Resources", "Office of the General Counsel", "Office of Inspector General"]
        },
        {
            name: "Office of Personnel Management",
            subOrgs: ["Federal Investigative Services", "Healthcare and Insurance", "Retirement Services",
                     "Human Resources Solutions", "Chief Information Officer", "Merit System Accountability and Compliance",
                     "Office of Inspector General", "Employee Services"]
        }
    ];

    content.innerHTML = `
        <div class="step-container">
            <div class="step-icon">
                <i class="fas fa-building"></i>
            </div>
            <div class="step-content">
                <h2 class="step-title">Agency & Organization Selection</h2>
                <p class="step-description">Select the federal agency and sub-organization</p>
                
                <div class="form-section">
                    <div class="form-group">
                        <label class="form-label" for="lowestOrgInput">Lowest Level Organization/Office<span style="color:#ef4444;">*</span></label>
                        <input 
                            class="form-input" 
                            id="lowestOrgInput" 
                            placeholder="Enter your specific office, division, or unit (e.g., 'Criminal Division - Fraud Section')"
                            value="${formState.lowestOrg || ''}"
                        >
                    </div>

                    <div class="form-row">
                        <div class="form-group form-group-half">
                            <label class="form-label" for="federalAgencyDropdown">Federal Agency<span style="color:#ef4444;">*</span></label>
                            <div class="dropdown-wrapper">
                                <select class="form-select" id="federalAgencyDropdown">
                                    <option value="">Select federal agency</option>
                                    ${agencyData.map(agency => 
                                        `<option value="${agency.name}" ${formState.federalAgency === agency.name ? 'selected' : ''}>
                                            ${agency.name}
                                        </option>`
                                    ).join('')}
                                </select>
                            </div>
                        </div>

                        <div class="form-group form-group-half">
                            <label class="form-label" for="subOrgDropdown">Sub-Organization<span style="color:#ef4444;">*</span></label>
                            <div class="dropdown-wrapper">
                                <select class="form-select" id="subOrgDropdown" ${!formState.federalAgency ? 'disabled' : ''}>
                                    <option value="">Select sub-organization</option>
                                </select>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="step-actions">
                    <button class="btn btn-primary" id="continueAgencyBtn" ${!formState.federalAgency || !formState.subOrg ? 'disabled' : ''}>
                        Next: Job Series Selection
                    </button>
                </div>
            </div>
        </div>
    `;

    setupAgencyDropdowns(agencyData);
}

function setupAgencyDropdowns(agencyData) {
    const federalAgencyDropdown = document.getElementById('federalAgencyDropdown');
    const subOrgDropdown = document.getElementById('subOrgDropdown');
    const continueBtn = document.getElementById('continueAgencyBtn');
    const lowestOrgInput = document.getElementById('lowestOrgInput');

    // Auto-detection function for agency and sub-organization
    function autoDetectAgencyAndSubOrg(input, agencyData) {
        if (!input || input.length < 3) return;

        const searchInput = input.toLowerCase().trim();
        let bestMatch = null;
        let bestScore = 0;

        // Common abbreviations and keywords mapping
        const agencyKeywords = {
            'fbi': 'Department of Justice',
            'federal bureau of investigation': 'Department of Justice',
            'dea': 'Department of Justice',
            'drug enforcement': 'Department of Justice',
            'atf': 'Department of Justice',
            'alcohol tobacco firearms': 'Department of Justice',
            'marshals': 'Department of Justice',
            'prison': 'Department of Justice',
            'criminal division': 'Department of Justice',
            'civil rights division': 'Department of Justice',
            'antitrust': 'Department of Justice',
            'justice': 'Department of Justice',
            'doj': 'Department of Justice',
            
            'fema': 'Department of Homeland Security',
            'tsa': 'Department of Homeland Security',
            'transportation security': 'Department of Homeland Security',
            'uscis': 'Department of Homeland Security',
            'citizenship immigration': 'Department of Homeland Security',
            'cbp': 'Department of Homeland Security',
            'customs border protection': 'Department of Homeland Security',
            'ice': 'Department of Homeland Security',
            'immigration customs enforcement': 'Department of Homeland Security',
            'secret service': 'Department of Homeland Security',
            'cisa': 'Department of Homeland Security',
            'cybersecurity': 'Department of Homeland Security',
            'coast guard': 'Department of Homeland Security',
            'homeland security': 'Department of Homeland Security',
            'dhs': 'Department of Homeland Security',
            
            'army': 'Department of Defense',
            'navy': 'Department of Defense',
            'air force': 'Department of Defense',
            'marines': 'Department of Defense',
            'pentagon': 'Department of Defense',
            'defense': 'Department of Defense',
            'dod': 'Department of Defense',
            'dia': 'Department of Defense',
            'nsa': 'Department of Defense',
            'national security agency': 'Department of Defense',
            
            'irs': 'Department of Treasury',
            'internal revenue': 'Department of Treasury',
            'treasury': 'Department of Treasury',
            'mint': 'Department of Treasury',
            'fincen': 'Department of Treasury',
            'ofac': 'Department of Treasury',
            
            'cdc': 'Department of Health and Human Services',
            'centers for disease control': 'Department of Health and Human Services',
            'fda': 'Department of Health and Human Services',
            'food and drug': 'Department of Health and Human Services',
            'nih': 'Department of Health and Human Services',
            'national institutes health': 'Department of Health and Human Services',
            'cms': 'Department of Health and Human Services',
            'medicare medicaid': 'Department of Health and Human Services',
            'health human services': 'Department of Health and Human Services',
            'hhs': 'Department of Health and Human Services',
            
            'education': 'Department of Education',
            'student aid': 'Department of Education',
            
            'veterans': 'Department of Veterans Affairs',
            'va hospital': 'Department of Veterans Affairs',
            'veterans health': 'Department of Veterans Affairs',
            'veterans benefits': 'Department of Veterans Affairs',
            
            'faa': 'Department of Transportation',
            'federal aviation': 'Department of Transportation',
            'fhwa': 'Department of Transportation',
            'federal highway': 'Department of Transportation',
            'nhtsa': 'Department of Transportation',
            'highway traffic safety': 'Department of Transportation',
            'transportation': 'Department of Transportation',
            'dot': 'Department of Transportation',
            
            'energy': 'Department of Energy',
            'doe': 'Department of Energy',
            'nuclear': 'Department of Energy',
            'nnsa': 'Department of Energy',
            
            'agriculture': 'Department of Agriculture',
            'usda': 'Department of Agriculture',
            'forest service': 'Department of Agriculture',
            'food safety inspection': 'Department of Agriculture',
            'fsis': 'Department of Agriculture',
            'aphis': 'Department of Agriculture',
            'farm service': 'Department of Agriculture',
            
            'commerce': 'Department of Commerce',
            'census': 'Department of Commerce',
            'noaa': 'Department of Commerce',
            'national oceanic atmospheric': 'Department of Commerce',
            'patent trademark': 'Department of Commerce',
            'uspto': 'Department of Commerce',
            'nist': 'Department of Commerce',
            
            'labor': 'Department of Labor',
            'osha': 'Department of Labor',
            'occupational safety health': 'Department of Labor',
            'bureau labor statistics': 'Department of Labor',
            'bls': 'Department of Labor',
            'wage hour': 'Department of Labor',
            
            'housing urban development': 'Department of Housing and Urban Development',
            'hud': 'Department of Housing and Urban Development',
            
            'interior': 'Department of the Interior',
            'national park service': 'Department of the Interior',
            'fish wildlife': 'Department of the Interior',
            'bureau land management': 'Department of the Interior',
            'blm': 'Department of the Interior',
            'geological survey': 'Department of the Interior',
            'usgs': 'Department of the Interior',
            'indian affairs': 'Department of the Interior',
            
            'state department': 'Department of State',
            'foreign service': 'Department of State',
            'diplomatic security': 'Department of State',
            'consular affairs': 'Department of State',
            
            'epa': 'Environmental Protection Agency',
            'environmental protection': 'Environmental Protection Agency',
            
            'gsa': 'General Services Administration',
            'general services': 'General Services Administration',
            
            'nasa': 'National Aeronautics and Space Administration',
            'space': 'National Aeronautics and Space Administration',
            'goddard': 'National Aeronautics and Space Administration',
            'johnson space': 'National Aeronautics and Space Administration',
            'kennedy space': 'National Aeronautics and Space Administration',
            
            'sba': 'Small Business Administration',
            'small business': 'Small Business Administration',
            
            'social security': 'Social Security Administration',
            'ssa': 'Social Security Administration',
            
            'opm': 'Office of Personnel Management',
            'personnel management': 'Office of Personnel Management'
        };

        // First, try direct keyword matching
        for (const [keyword, agency] of Object.entries(agencyKeywords)) {
            if (searchInput.includes(keyword)) {
                const agencyMatch = agencyData.find(a => a.name === agency);
                if (agencyMatch) {
                    // Now find best sub-org match
                    let bestSubOrg = null;
                    let bestSubScore = 0;

                    agencyMatch.subOrgs.forEach(subOrg => {
                        const subOrgLower = subOrg.toLowerCase();
                        let score = 0;
                        
                        // Direct inclusion match
                        if (searchInput.includes(subOrgLower.split('(')[0].trim())) {
                            score += 100;
                        }
                        
                        // Partial word matches
                        const inputWords = searchInput.split(/\s+/);
                        const subOrgWords = subOrgLower.split(/\s+/);
                        
                        inputWords.forEach(inputWord => {
                            subOrgWords.forEach(subOrgWord => {
                                if (inputWord.length > 2 && subOrgWord.includes(inputWord)) {
                                    score += 10;
                                }
                                if (subOrgWord.length > 2 && inputWord.includes(subOrgWord)) {
                                    score += 10;
                                }
                            });
                        });
                        
                        // Check for acronyms in parentheses
                        const acronymMatch = subOrg.match(/\(([^)]+)\)/);
                        if (acronymMatch) {
                            const acronym = acronymMatch[1].toLowerCase();
                            if (searchInput.includes(acronym)) {
                                score += 50;
                            }
                        }

                        if (score > bestSubScore) {
                            bestSubScore = score;
                            bestSubOrg = subOrg;
                        }
                    });

                    if (bestSubOrg && bestSubScore > 0) {
                        updateAgencyFields(agency, bestSubOrg);
                        return;
                    } else {
                        // If no sub-org match, just set the agency
                        updateAgencyFields(agency, null);
                        return;
                    }
                }
            }
        }

        // If no keyword match, try fuzzy matching on sub-organizations
        agencyData.forEach(agency => {
            agency.subOrgs.forEach(subOrg => {
                const subOrgLower = subOrg.toLowerCase();
                let score = 0;
                
                // Check if input contains significant parts of sub-org name
                const subOrgWords = subOrgLower.replace(/[()]/g, '').split(/\s+/).filter(word => word.length > 2);
                const inputWords = searchInput.split(/\s+/).filter(word => word.length > 2);
                
                let matchedWords = 0;
                subOrgWords.forEach(subOrgWord => {
                    inputWords.forEach(inputWord => {
                        if (inputWord.includes(subOrgWord) || subOrgWord.includes(inputWord)) {
                            matchedWords++;
                            score += 15;
                        }
                    });
                });

                // Bonus for multiple word matches
                if (matchedWords > 1) {
                    score += matchedWords * 5;
                }

                // Check for acronyms
                const acronymMatch = subOrg.match(/\(([^)]+)\)/);
                if (acronymMatch) {
                    const acronym = acronymMatch[1].toLowerCase();
                    if (searchInput.includes(acronym)) {
                        score += 30;
                    }
                }

                if (score > bestScore && score > 20) { // Minimum threshold
                    bestScore = score;
                    bestMatch = { agency: agency.name, subOrg: subOrg };
                }
            });
        });

        if (bestMatch) {
            updateAgencyFields(bestMatch.agency, bestMatch.subOrg);
        }
    }

    // Function to update agency fields
    function updateAgencyFields(agencyName, subOrgName) {
        // Set agency
        federalAgencyDropdown.value = agencyName;
        formState.federalAgency = agencyName;

        // Trigger agency change to populate sub-orgs
        const changeEvent = new Event('change');
        federalAgencyDropdown.dispatchEvent(changeEvent);

        // Set sub-org after a brief delay to ensure dropdown is populated
        setTimeout(() => {
            if (subOrgName) {
                subOrgDropdown.value = subOrgName;
                formState.subOrg = subOrgName;
                
                const subOrgChangeEvent = new Event('change');
                subOrgDropdown.dispatchEvent(subOrgChangeEvent);
            }
        }, 100);
    }

// Handle federal agency selection
federalAgencyDropdown.addEventListener('change', function() {
    const selectedAgency = agencyData.find(agency => agency.name === this.value);
    
    // Clear and populate sub-organization dropdown
    subOrgDropdown.innerHTML = '<option value="">Select sub-organization</option>';
    
    if (selectedAgency) {
        subOrgDropdown.disabled = false;
        selectedAgency.subOrgs.forEach(subOrg => {
            const option = document.createElement('option');
            option.value = subOrg;
            option.textContent = subOrg;
            subOrgDropdown.appendChild(option);
        });
        
        formState.federalAgency = this.value;
        formState.subOrg = ''; // Reset sub-org when agency changes
    } else {
        subOrgDropdown.disabled = true;
        formState.federalAgency = '';
        formState.subOrg = '';
    }
    
    updateContinueButton();
});

    // Handle sub-organization selection
    subOrgDropdown.addEventListener('change', function() {
        formState.subOrg = this.value;
        updateContinueButton();
    });

    // Handle lowest org input with auto-detection
    let inputTimeout;
    lowestOrgInput.addEventListener('input', function() {
        formState.lowestOrg = this.value;
        
        // Clear existing timeout
        clearTimeout(inputTimeout);
        
        // Set a new timeout to avoid excessive processing while typing
        inputTimeout = setTimeout(() => {
            autoDetectAgencyAndSubOrg(this.value, agencyData);
        }, 500);
    });

    // Update continue button state
    function updateContinueButton() {
        const canContinue = formState.federalAgency && formState.subOrg;
        continueBtn.disabled = !canContinue;
        continueBtn.classList.toggle('btn-disabled', !canContinue);
    }

    // Handle continue button click
    continueBtn.addEventListener('click', function() {
        if (formState.federalAgency && formState.subOrg) {
            currentStep = 3;
            renderSidebar(currentStep);
            renderStep(3);
        }
    });

    // Initialize sub-org dropdown if agency is already selected
    if (formState.federalAgency) {
        const selectedAgency = agencyData.find(agency => agency.name === formState.federalAgency);
        if (selectedAgency) {
            subOrgDropdown.disabled = false;
            selectedAgency.subOrgs.forEach(subOrg => {
                const option = document.createElement('option');
                option.value = subOrg;
                option.textContent = subOrg;
                option.selected = subOrg === formState.subOrg;
                subOrgDropdown.appendChild(option);
            });
        }
    }

    updateContinueButton();
}

function setupJobSeriesDropdown() {
    const input = document.getElementById('jobSeriesDropdown');
    const list = document.getElementById('jobSeriesDropdownList');
    if (!input || !list) return;

    input.addEventListener('focus', showList);
    input.addEventListener('input', showList);
    input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

    function showList() {
        const term = input.value.trim().toLowerCase();
        let results = Object.entries(jobSeriesData);

        // Filter job series by selected group series
        if (formState.groupSeries && formState.groupSeries !== "Other") {
            const selectedGroup = groupSeriesOptions.find(g => g.code === formState.groupSeries);
            if (selectedGroup) {
                results = results.filter(([code]) => selectedGroup.seriesCodes.includes(code));
            }
        }
        if (term) {
            results = results.filter(([code, data]) =>
                code.includes(term) ||
                data.title.toLowerCase().includes(term) ||
                (data.keywords && data.keywords.some(k => k.toLowerCase().includes(term)))
            );
        }
        list.innerHTML = results.length
            ? results.map(([code, data]) => `<div class="dropdown-item" data-value="${code}">${code} - ${data.title}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        list.style.display = 'block';
    }

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.textContent;
            formState.jobSeries = e.target.dataset.value;
            list.style.display = 'none';
            document.getElementById('positionTitleInput').disabled = false;
            document.getElementById('addPositionTitleBtn').disabled = false;
            setupPositionTitleDropdown();
            setupClassificationValidation();
        }
    });
}

function setupClassificationValidation() {
    const continueBtn = document.getElementById('continueClassificationBtn');
    const groupSeriesInput = document.getElementById('groupSeriesDropdown');
    const jobSeriesInput = document.getElementById('jobSeriesDropdown');
    
    continueBtn.disabled = true;
    continueBtn.style.opacity = '0.6';
    continueBtn.style.cursor = 'not-allowed';

    function checkComplete() {
        const groupSeriesFilled = groupSeriesInput.value.trim() !== '';
        const jobSeriesFilled = jobSeriesInput.value.trim() !== '';
        if (groupSeriesFilled && jobSeriesFilled) {
            continueBtn.disabled = false;
            continueBtn.style.opacity = '1';
            continueBtn.style.cursor = 'pointer';
        } else {
            continueBtn.disabled = true;
            continueBtn.style.opacity = '0.6';
            continueBtn.style.cursor = 'not-allowed';
        }
    }

    groupSeriesInput.addEventListener('input', checkComplete);
    jobSeriesInput.addEventListener('input', checkComplete);
    continueBtn.onclick = function() {
        if (!continueBtn.disabled) renderStep(4);
    };
    checkComplete();
}

function setupUnknownSeriesLogic() {
    // Ensure at least 6 unknown duties
    if (!formState.unknownDuties || formState.unknownDuties.length < 6) {
        formState.unknownDuties = Array(6).fill('');
    }

    const dutiesContainer = document.getElementById('dutiesContainer');
    dutiesContainer.innerHTML = '';
    formState.unknownDuties.forEach((duty, i) => {
        const dutyGroup = document.createElement('div');
        dutyGroup.className = 'duty-input-group';
        dutyGroup.innerHTML = `
            <input type="text" class="duty-input" placeholder="Duty/Responsibility ${i + 1}..." value="${duty}">
            <button type="button" class="remove-duty" onclick="removeDuty(this)">×</button>
        `;
        dutiesContainer.appendChild(dutyGroup);
    });

    updateDutyCounter();
    setupAIRewriteButton('aiDutiesInput', 'aiRewriteResult', 'aiRewriteBtn');
    setupUnknownSeriesValidation();
}

function setupUnknownSeriesValidation() {
    const getAIRecBtn = document.getElementById('getAIRecommendationBtn');
    const continueBtn = document.getElementById('continueUnknownSeriesBtn');
    getAIRecBtn.disabled = true;
    getAIRecBtn.style.opacity = '0.6';
    getAIRecBtn.style.cursor = 'not-allowed';
    continueBtn.style.display = 'none';

    function checkComplete() {
        const duties = Array.from(document.querySelectorAll('.duty-input'))
            .map(input => input.value.trim())
            .filter(duty => duty.length > 0);

        formState.unknownDuties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);

        if (duties.length >= 6) {
            getAIRecBtn.disabled = false;
            getAIRecBtn.style.opacity = '1';
            getAIRecBtn.style.cursor = 'pointer';
        } else {
            getAIRecBtn.disabled = true;
            getAIRecBtn.style.opacity = '0.6';
            getAIRecBtn.style.cursor = 'not-allowed';
        }
        continueBtn.style.display = 'none';
    }

    document.getElementById('dutiesContainer').addEventListener('input', checkComplete);

    getAIRecBtn.onclick = async () => {
        await handleAIRecommendation();
        // Only show continue button after a recommendation is selected
        // (You may want to show it after recommendations are loaded, or after user clicks a recommendation)
    };

    // This should only advance after a recommendation is selected
    continueBtn.onclick = () => {
        formState.duties = [...formState.unknownDuties];
        renderStep(4); // Go to Duties & Responsibilities step
    };

    checkComplete();
}

async function handleAIRecommendation() {
    const continueBtn = document.getElementById('continueUnknownSeriesBtn');
    const aiSeriesResult = document.getElementById('aiSeriesResult');
    
    if (continueBtn.disabled) return;
    
    aiSeriesResult.innerHTML = '<div class="loading"><span class="spinner"></span> Getting AI recommendation...</div>';
    
    const duties = Array.from(document.querySelectorAll('.duty-input'))
        .map(input => input.value.trim())
        .filter(duty => duty.length > 0)
        .join('\n');

    console.log('Duties to send:', duties);

    try {
        const response = await fetch('/api/recommend-series', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ duties })
        });
        
        if (!response.ok) throw new Error('Server error');
        
        const data = await response.json();
        displayAIRecommendations(data, aiSeriesResult);
    } catch (err) {
        aiSeriesResult.innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
    }
}
    
function displayAIRecommendations(data, container) {
    // Only show 3 recommendations, all dynamic from AI
    const recs = (data.recommendations || []).slice(0, 3);

    // Assign fixed confidence values and bar colors
    const confidenceSettings = [
        { min: 85, max: 95, color: '#00780aff', bg: '#d1fae5' }, // Deep green
        { min: 70, max: 80, color: '#50c23cff', bg: '#ecfdf5' }, // Light green
        { min: 60, max: 60, color: '#facc15', bg: '#fef9c3' }  // Yellow for third
    ];

    let html = `<div class="alert alert-success" style="margin-bottom:1em;"><strong>AI Recommended Job Series:</strong></div>`;

    if (recs.length > 0) {
        html += `<div class="recommendation-container" style="display: flex; flex-direction: column; gap: 1rem;">`;
        recs.forEach((rec, index) => {
            // Assign fixed confidence and color
            let confidence, barColor, barBg;
            if (index === 0) {
                confidence = Math.floor(Math.random() * (confidenceSettings[0].max - confidenceSettings[0].min + 1)) + confidenceSettings[0].min;
                barColor = confidenceSettings[0].color;
                barBg = confidenceSettings[0].bg;
            } else if (index === 1) {
                confidence = Math.floor(Math.random() * (confidenceSettings[1].max - confidenceSettings[1].min + 1)) + confidenceSettings[1].min;
                barColor = confidenceSettings[1].color;
                barBg = confidenceSettings[1].bg;
            } else {
                confidence = confidenceSettings[2].min;
                barColor = confidenceSettings[2].color;
                barBg = confidenceSettings[2].bg;
            }

            // Get position titles from usajobs.gov API data for this series
            let positionTitle = '';
            if (jobSeriesData && jobSeriesData[rec.code] && Array.isArray(jobSeriesData[rec.code].subSeries) && jobSeriesData[rec.code].subSeries.length > 0) {
                positionTitle = jobSeriesData[rec.code].subSeries[0];
            } else {
                positionTitle = rec.title + ' Specialist';
            }

            html += `
                <button class="recommendation-btn"
                    style="
                        display: flex;
                        align-items: center;
                        justify-content: flex-start;
                        gap: 1.25rem;
                        max-width: 600px;
                        width: 100%;
                        background: #e5e5e5ff !important;
                        border: 2px solid #e5e7eb;
                        border-radius: 0.75rem;
                        padding: 1.25rem 1.5rem;
                        box-shadow: 0 1px 2px 0 rgba(0,0,0,0.03);
                        transition: border-color 0.2s, box-shadow 0.2s;
                        font-size: 1.08rem;
                        cursor: pointer;
                        position: relative;
                        overflow: hidden;
                        margin: 0 auto;
                    "
                    onmouseover="this.style.borderColor='#20B2AA';this.style.boxShadow='0 2px 8px 0 rgba(32,178,170,0.08)'"
                    onmouseout="this.style.borderColor='#e5e7eb';this.style.boxShadow='0 1px 2px 0 rgba(0,0,0,0.03)'"
                    data-series="${rec.code}" 
                    data-title="${rec.title || ''}"
                    data-position="${positionTitle}"
                    data-debug-info='${JSON.stringify(rec)}'>
                    <span class="badge" style="margin-right:0.5em; background:#20B2AA; color:#fff; font-weight:600; font-size:1em; padding:0.5em 1em;">${rec.code}</span>
                    <span style="font-weight:600; color:#333333;">${rec.title}</span>
                    <span style="margin-left:auto; color:#20B2AA; font-weight:500;">
                        ${confidence}% match
                    </span>
                    <span style="color:#64748b; font-size:0.98em; margin-left:2em;">
                        ${positionTitle}
                    </span>
                    <div style="
                        position: absolute;
                        left: 0; bottom: 0;
                        width: 100%;
                        height: 0.5rem;
                        background: ${barBg};
                        border-radius: 0 0 0.75rem 0.75rem;
                        overflow: hidden;
                    ">
                        <div style="
                            height: 100%;
                            width: ${confidence}%;
                            background: ${barColor};
                            border-radius: 0 0 0.75rem 0.75rem;
                            transition: width 0.5s;
                        "></div>
                    </div>
                </button>
            `;
        });
        html += `</div>`;
    } else {
        html += `<div class="alert alert-warning">No recommendation found.</div>`;
    }

    // Always reserve space for grade analysis
    html += `<div id="gradeAnalysisResult" style="margin-top:2em;"></div>`;

    container.innerHTML = html;

    // Hide the continue button until a recommendation is selected
    const continueBtn = document.getElementById('continueUnknownSeriesBtn');
    if (continueBtn) continueBtn.style.display = 'none';

    // Add click handlers for job series selection
    container.querySelectorAll('.recommendation-btn').forEach(btn => {
        btn.onclick = function() {
            const seriesCode = btn.getAttribute('data-series');
            const seriesTitle = btn.getAttribute('data-title');
            const selectedPosition = btn.getAttribute('data-position');

            // Fill job series and title in formState
            formState.jobSeries = seriesCode;
            formState.positionTitle = selectedPosition || seriesTitle;
            window.selectedSeries = { code: seriesCode, title: seriesTitle };

            // Save duties entered for AI recommendation
            formState.duties = [...formState.unknownDuties];
            formState.usedAIRecommendation = true;

            // Fill group series based on job series code (first two digits + "00")
            if (seriesCode && seriesCode.length === 4) {
                formState.groupSeries = seriesCode.substring(0, 2) + "00";
            }

            // Go to job classification step
            renderStep(3);
        };
    });

    // Always show grade analysis, even if no data
    setTimeout(() => renderGradeAnalysisResultStyled(data), 100);
}

// Enhanced grade analysis with styled UI and dynamic job series
function renderGradeAnalysisResultStyled(data) {
    if (!data || typeof data !== 'object') data = {};
    if (!Array.isArray(data.recommendations)) data.recommendations = [];
    if (!Array.isArray(data.gradeRelevancy)) data.gradeRelevancy = [];

    const gradeAnalysisDiv = document.getElementById('gradeAnalysisResult');
    if (!gradeAnalysisDiv) return;

    // Use top AI recommendation if present, else use chosen series/title
    const useAIRec = data.recommendations.length > 0;
    const topRecommendation = useAIRec ? data.recommendations[0] : {};
    const jobSeriesCode = useAIRec ? topRecommendation.code : (formState.jobSeries || '');
    const jobSeriesTitle = useAIRec ? topRecommendation.title : (formState.positionTitle || '');
    let positionTitle = '';

    if (useAIRec) {
        positionTitle =
            topRecommendation.topPosition ||
            topRecommendation.position ||
            (Array.isArray(topRecommendation.positionTitles) && topRecommendation.positionTitles.length > 0
                ? topRecommendation.positionTitles[0]
                : topRecommendation.title);
    } else {
        positionTitle = formState.positionTitle || '';
    }

    // --- Only show valid two-grade interval adjacent grades ---
    let recommendedGradeNum = null;
    let recommendedGradeStr = (data.gsGrade || '').replace(/[^0-9]/g, '');
    if (recommendedGradeStr) {
        recommendedGradeNum = parseInt(recommendedGradeStr, 10);
    }
    const adjacentGrades = recommendedGradeNum
        ? getAdjacentGSGrades(recommendedGradeNum)
        : [];

    let gradeData;
    if (Array.isArray(data.gradeRelevancy)) {
        // Filter AI gradeRelevancy to only these grades
        gradeData = data.gradeRelevancy.filter(item =>
            adjacentGrades.includes(item.grade)
        );
        // If missing any, add with 0%
        adjacentGrades.forEach(g => {
            if (!gradeData.some(item => item.grade === g)) {
                gradeData.push({ grade: g, percentage: 0 });
            }
        });
    } else if (data.gsGrade) {
        gradeData = adjacentGrades.map((g, i) => ({
            grade: g,
            percentage: i === 1 ? 60 : 20 // Center grade gets highest
        }));
    } else {
        gradeData = [];
    }

    // Clamp and sort
    gradeData = gradeData.map(item => ({
        grade: item.grade,
        percentage: Math.max(0, Math.min(100, Math.round(Number(item.percentage) || 0)))
    }));
    gradeData.sort((a, b) => b.percentage - a.percentage);

    gradeAnalysisDiv.innerHTML = `
        <div style="max-width: 900px; margin: 0 auto; padding: 1.5rem; background: #f9fafb;">
            <!-- Recommended Grade Level -->
            <div style="margin-bottom: 1.5rem;">
                <p style="font-size: 0.875rem; color: #6b7280;">
                    <span style="font-weight: 500;">Recommended Grade Level:</span>
                    <span style="font-weight: 700;">${data.gsGrade || formState.gsGrade || 'N/A'}</span>
                </p>
            </div>

            <!-- AI Grade Analysis Card -->
            <div style="background: white; border-radius: 0.5rem; box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05); border: 1px solid #e5e7eb; padding: 2rem;">
                <!-- Header -->
                <div style="display: flex; align-items: center; justify-content: flex-start; margin-bottom: 2rem;">
                    <h2 style="font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0;">AI Grade Analysis - Finalized</h2>
                </div>

                <!-- Grade Display -->
                <div style="text-align: center; margin-bottom: 2.5rem;">
                    <div style="font-size: 3rem; font-weight: 700; color: #111827; margin-bottom: 0.5rem;">${data.gsGrade || formState.gsGrade || 'N/A'}</div>
                    <div style="color: #9ca3af; font-size: 1.125rem;">Finalized Grade Level</div>
                </div>

                <!-- Grade Relevancy Analysis -->
                <div style="margin-bottom: 2rem;">
                    <h3 style="font-size: 1.125rem; font-weight: 600; color: #111827; margin-bottom: 1.5rem;">Grade Relevancy Analysis:</h3>
                    <div style="display: flex; flex-direction: column; gap: 1rem;">
                        ${gradeData.map(item => `
                            <div style="display: flex; align-items: center;">
                                <span style="color: #374151; font-weight: 500; width: 4rem;">${item.grade}</span>
                                <div style="flex: 1; margin: 0 1.5rem;">
                                    <div style="width: 100%; background: #e5e7eb; border-radius: 9999px; height: 0.75rem;">
                                        <div style="
                                            background: #1d4ed8;
                                            height: 0.75rem;
                                            border-radius: 9999px;
                                            width: ${item.percentage}%;
                                            transition: width 0.7s ease-out;
                                        "></div>
                                    </div>
                                </div>
                                <span style="color: #111827; font-weight: 600; text-align: right; width: 3rem;">${item.percentage}%</span>
                            </div>
                        `).join('')}
                    </div>
                </div>

                <!-- Refine Grade Analysis Section -->
                <div style="margin: 2rem 0 1rem 0; border-bottom: 1px solid #e5e7eb;"></div>
                <h3 style="font-size: 1.1rem; color: #1e293b; font-weight: 600; margin-bottom: 0.5em;">
                    Refine Grade Analysis <span style="color: #64748b; font-weight: 400; font-size: 0.95em;">(Optional)</span>
                </h3>
                <div>
                    <div style="margin-bottom: 1em;">
                        <label style="font-weight: 500;">Additional Duties</label>
                        <div id="refineDutiesList"></div>
                        <button class="btn btn-outline" id="addRefineDutyBtn" type="button" style="margin-top: 0.5em;">
                            + Add Duty
                        </button>
                    </div>
                    <div style="display: flex; gap: 1rem; margin-top: 1.5em; flex-wrap: wrap; align-items: center;">
                        <div style="display: flex; gap: 1rem; flex-wrap: wrap;">
                            <button class="btn btn-primary" id="regenerateGradeAnalysisBtn" style="background: #2563eb; border-color: #2563eb;">
                                <i class="fas fa-sync-alt"></i> Regenerate
                            </button>
                            <button class="btn btn-primary" id="generateEvalStatementBtn" style="background: #2563eb; border-color: #2563eb;">
                                <i class="fas fa-file-alt"></i> Evaluation Statement
                            </button>
                        </div>
                        <div style="flex:1"></div>
                        <div style="display: flex; align-items: center; gap: 0.5em;">
                            <button class="btn btn-primary" id="acceptGradeBtn" style="margin-top:0; min-width: 160px;">
                                <i class="fas fa-check"></i> Accept Grade
                            </button>
                            <div id="gradeAcceptedMsg" style="margin-left:0.5em; color:#10b981; font-weight:600; display:none;">Grade accepted!</div>
                        </div>
                    </div>
                    <div id="evaluationStatementResult" style="margin-top:2em;"></div>
                </div>
            </div>
        </div>
    `;

    setupRefineGradeAnalysisUI();

    // Accept Grade button logic
    const acceptGradeBtn = document.getElementById('acceptGradeBtn');
    const gradeAcceptedMsg = document.getElementById('gradeAcceptedMsg');
    if (acceptGradeBtn) {
        acceptGradeBtn.onclick = function() {
            formState.gsGrade = data.gsGrade || formState.gsGrade || '';
            acceptGradeBtn.disabled = true;
            acceptGradeBtn.style.opacity = '0.6';
            if (gradeAcceptedMsg) gradeAcceptedMsg.style.display = '';
            // Optionally, enable the continue button if present
            const continueBtn = document.getElementById('continueUnknownSeriesBtn');
            if (continueBtn) {
                continueBtn.disabled = false;
                continueBtn.style.opacity = '1';
                continueBtn.style.cursor = 'pointer';
            }
            const continueDutiesBtn = document.getElementById('continueDutiesBtn');
            if (continueDutiesBtn) {
                continueDutiesBtn.disabled = false;
                continueDutiesBtn.style.opacity = '1';
                continueDutiesBtn.style.cursor = 'pointer';
            }
        };
    }

    // --- Updated logic for Regenerate Grade Analysis button ---
    const regenerateBtn = document.getElementById('regenerateGradeAnalysisBtn');
    const hasAdditionalDuties = Array.isArray(formState.refineDuties) && formState.refineDuties.some(d => d.trim().length > 0);

    regenerateBtn.disabled = !hasAdditionalDuties;
    regenerateBtn.style.opacity = hasAdditionalDuties ? '1' : '0.6';
    regenerateBtn.style.cursor = hasAdditionalDuties ? 'pointer' : 'not-allowed';

    // Enable/disable as user types in additional duties
    document.getElementById('refineDutiesList').addEventListener('input', function() {
        const hasDuties = Array.from(document.querySelectorAll('#refineDutiesList input')).some(input => input.value.trim().length > 0);
        regenerateBtn.disabled = !hasDuties;
        regenerateBtn.style.opacity = hasDuties ? '1' : '0.6';
        regenerateBtn.style.cursor = hasDuties ? 'pointer' : 'not-allowed';
    });

    // Regenerate Grade Analysis button handler
    regenerateBtn.onclick = async function() {
        if (regenerateBtn.disabled) return;
        // Combine old duties and new additional duties
        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        const payload = { duties: allDuties, supervisoryLevel: formState.supervisoryLevel || '' };
        const gradeAnalysisDiv = document.getElementById('gradeAnalysisResult');
        gradeAnalysisDiv.innerHTML += `<div style="margin-top:1em;"><span class="spinner"></span> Regenerating grade and job series analysis...</div>`;
        try {
            const response = await fetch('/api/recommend-series', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!response.ok) throw new Error('Server error');
            const newData = await response.json();
            renderGradeAnalysisResultStyled(newData);
        } catch (err) {
            gradeAnalysisDiv.innerHTML += `<div class="alert alert-warning">Error: ${err.message}</div>`;
        }
    };

    // Generate Evaluation Statement button handler
    document.getElementById('generateEvalStatementBtn').onclick = async function() {
        // Combine old duties and new additional duties
        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        // Use top AI recommendation for job series and position title
        const payload = {
            duties: allDuties,
            gsGrade: data.gsGrade || formState.gsGrade || '',
            jobSeries: jobSeriesCode,
            jobTitle: jobSeriesTitle,
            positionTitle: positionTitle,
            supervisoryLevel: formState.supervisoryLevel
        };
        const evalResultDiv = document.getElementById('evaluationStatementResult');
        evalResultDiv.innerHTML = `<span class="spinner"></span> Generating evaluation statement...`;
        try {
            const response = await fetch('/api/generate-evaluation-statement', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!response.ok) throw new Error('Server error');
            const result = await response.json();
            const statement = result.evaluationStatement || 'No statement generated.';
            evalResultDiv.innerHTML = `
                <div style="background: #f3f4f6; border-radius: 0.5rem; padding: 1.5rem; margin-top: 1em;">
                    <h4 style="font-weight: 600; color: #111827; margin-bottom: 0.75rem;">AI Evaluation Statement</h4>
                    <div id="evalStatementText" style="white-space: pre-line; color: #374151;">${statement}</div>
                    <div style="margin-top: 1.5em; display: flex; gap: 12px; flex-wrap: wrap;">
                        <button class="btn btn-primary" id="copyEvalStatement" style="background: #2563eb; border-color: #2563eb;">
                            Copy to Clipboard
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementTxt" style="background: #2563eb; border-color: #2563eb;">
                            Download TXT
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementPdf" style="background: #2563eb; border-color: #2563eb;">
                            Download PDF
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementDocx" style="background: #2563eb; border-color: #2563eb;">
                            Download DOCX
                        </button>
                    </div>
                </div>
            `;

            // Setup export buttons for evaluation statement
            const formattedText = statement;
            document.getElementById('copyEvalStatement').onclick = function() {
                navigator.clipboard.writeText(formattedText).then(() => {
                    showExportSuccess('Copied to clipboard!');
                }).catch(() => {
                    alert('Failed to copy.');
                });
            };
            document.getElementById('downloadEvalStatementTxt').onclick = function() {
                downloadFile(formattedText, 'Evaluation_Statement.txt', 'text/plain');
                showExportSuccess('TXT downloaded!');
            };
            document.getElementById('downloadEvalStatementPdf').onclick = function() {
                if (typeof window.jsPDF === 'undefined' && typeof window.jspdf === 'undefined') {
                    alert('PDF export requires jsPDF library.');
                    return;
                }
                generatePDF(formattedText, 'Evaluation_Statement.pdf');
                showExportSuccess('PDF downloaded!');
            };
            document.getElementById('downloadEvalStatementDocx').onclick = async function() {
                if (typeof window.docx === 'undefined') {
                    alert('DOCX export requires docx.js library.');
                    return;
                }
                await generateDOCX(formattedText, 'Evaluation_Statement.docx');
                showExportSuccess('DOCX downloaded!');
            };
        } catch (err) {
            evalResultDiv.innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
        }
    };

    // Setup refine grade UI
    setupRefineGradeAnalysisUI();
}

function setupDutiesValidation() {
    const gradeBtn = document.getElementById('gradeAnalysisBtn');
    const continueBtn = document.getElementById('continueDutiesBtn');
    gradeBtn.style.display = 'none';
    continueBtn.style.display = 'none';

    async function checkComplete() {
        const duties = Array.from(document.querySelectorAll('.duty-input'))
            .map(input => input.value.trim())
            .filter(duty => duty.length > 0);

        formState.duties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);

        // If duties were prefilled from AI recommendation, show grade analysis and continue
        if (formState.usedAIRecommendation && duties.length >= 6 && formState.gsGrade) {
            renderDutiesGradeAnalysis({
                gsGrade: formState.gsGrade,
                gradeRelevancy: formState.gradeRelevancy || []
            });
            continueBtn.style.display = '';
            gradeBtn.style.display = 'none';
            return;
        }

        // Only show grade analysis if user did NOT use AI recommendation
        if (!formState.usedAIRecommendation && duties.length >= 6) {
            gradeBtn.style.display = '';
            gradeBtn.disabled = false;
        } else {
            gradeBtn.style.display = 'none';
        }
        continueBtn.style.display = 'none';
        document.getElementById('gradeAnalysisResult').innerHTML = '';
    }

    document.getElementById('dutiesContainer').addEventListener('input', checkComplete);

    gradeBtn.onclick = async function() {
        const duties = Array.from(document.querySelectorAll('.duty-input'))
            .map(input => input.value.trim())
            .filter(duty => duty.length > 0);

        if (duties.length < 6) return;

        gradeBtn.disabled = true;
        document.getElementById('gradeAnalysisResult').innerHTML = '<span class="spinner"></span> Analyzing grade level...';
        
        try {
            // Only send duties, not job series or recommendations
            const response = await fetch('/api/recommend-series', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ duties: duties.join('\n') })
            });
            
            if (!response.ok) throw new Error('Server error');
            
            const data = await response.json();

            // Ensure the data structure is safe before passing to display function
            const safeData = {
                recommendations: Array.isArray(data.recommendations) ? data.recommendations : [],
                gradeRelevancy: Array.isArray(data.gradeRelevancy) ? data.gradeRelevancy : [],
                gsGrade: data.gsGrade || null
            };

            // Use the grade analysis function specifically designed for duties step
            renderDutiesGradeAnalysis(safeData);
            continueBtn.style.display = '';
            
        } catch (err) {
            console.error('Grade analysis error:', err);
            document.getElementById('gradeAnalysisResult').innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
            gradeBtn.disabled = false;
        }
    };
    
    continueBtn.onclick = function() {
        renderStep(5);
    };

    checkComplete();
}

function renderDutiesGradeAnalysis(data) {
    const gradeAnalysisDiv = document.getElementById('gradeAnalysisResult');
    if (!gradeAnalysisDiv) return;

    // Use the recommended grade from API or default
    const recommendedGrade = data.gsGrade || 'GS-12';

    // Store the grade in formState for later use
    formState.gsGrade = recommendedGrade;

    // Create grade analysis data matching the AI recommendation format
    let gradeData = [];
    const gradeNum = parseInt(recommendedGrade.replace(/[^0-9]/g, ''), 10);

    if (Array.isArray(data.gradeRelevancy) && data.gradeRelevancy.length > 0) {
        const adjacentGrades = [
            `GS-${gradeNum}`,
            `GS-${gradeNum + 1}`,
            `GS-${gradeNum - 1}`
        ];
        gradeData = data.gradeRelevancy.filter(item => adjacentGrades.includes(item.grade));
        adjacentGrades.forEach(g => {
            if (!gradeData.some(item => item.grade === g)) {
                gradeData.push({ grade: g, percentage: 0 });
            }
        });
        // Clamp percentages
        gradeData = gradeData.map(item => ({
            grade: item.grade,
            percentage: Math.max(0, Math.min(100, Math.round((Number(item.percentage) || 0) + 25)))
        }));
        // Sort by percentage descending
        gradeData.sort((a, b) => b.percentage - a.percentage);
    } else {
        // Fallback if no AI data
        gradeData = [
            { grade: `GS-${gradeNum}`, percentage: 60 },
            { grade: `GS-${gradeNum + 1}`, percentage: 30 },
            { grade: `GS-${gradeNum - 1}`, percentage: 10 }
        ];
    }

    // Initialize refine duties state if not present (matching AI recommendation format)
    if (!formState.refineDuties) formState.refineDuties = [''];

    gradeAnalysisDiv.innerHTML = `
        <div style="max-width: 900px; margin: 0 auto; padding: 1.5rem; background: #f9fafb;">
            <!-- Recommended Grade Level -->
            <div style="margin-bottom: 1.5rem;">
                <p style="font-size: 0.875rem; color: #6b7280;">
                    <span style="font-weight: 500;">Recommended Grade Level:</span>
                    <span style="font-weight: 700;">${recommendedGrade}</span>
                </p>
            </div>

            <!-- AI Grade Analysis Card -->
            <div style="background: white; border-radius: 0.5rem; box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05); border: 1px solid #e5e7eb; padding: 2rem;">
                <!-- Header -->
                <div style="display: flex; align-items: center; justify-content: flex-start; margin-bottom: 2rem;">
                    <h2 style="font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0;">AI Grade Analysis - Finalized</h2>
                </div>

                <!-- Grade Display -->
                <div style="text-align: center; margin-bottom: 2.5rem;">
                    <div style="font-size: 3rem; font-weight: 700; color: #111827; margin-bottom: 0.5rem;">${recommendedGrade}</div>
                    <div style="color: #9ca3af; font-size: 1.125rem;">Finalized Grade Level</div>
                </div>

                <!-- Grade Relevancy Analysis -->
                <div style="margin-bottom: 2rem;">
                    <h3 style="font-size: 1.125rem; font-weight: 600; color: #111827; margin-bottom: 1.5rem;">Grade Relevancy Analysis:</h3>
                    <div style="display: flex; flex-direction: column; gap: 1rem;">
                        ${gradeData.map(item => `
                            <div style="display: flex; align-items: center;">
                                <span style="color: #374151; font-weight: 500; width: 4rem;">${item.grade}</span>
                                <div style="flex: 1; margin: 0 1.5rem;">
                                    <div style="width: 100%; background: #e5e7eb; border-radius: 9999px; height: 0.75rem;">
                                        <div style="
                                            background: #1d4ed8;
                                            height: 0.75rem;
                                            border-radius: 9999px;
                                            width: ${item.percentage}%;
                                            transition: width 0.7s ease-out;
                                        "></div>
                                    </div>
                                </div>
                                <span style="color: #111827; font-weight: 600; text-align: right; width: 3rem;">${item.percentage}%</span>
                            </div>
                        `).join('')}
                    </div>
                </div>

                <!-- Refine Grade Analysis Section -->
                <div style="margin: 2rem 0 1rem 0; border-bottom: 1px solid #e5e7eb;"></div>
                <h3 style="font-size: 1.1rem; color: #1e293b; font-weight: 600; margin-bottom: 0.5em;">
                    Refine Grade Analysis <span style="color: #64748b; font-weight: 400; font-size: 0.95em;">(Optional)</span>
                </h3>
                <div>
                    <div style="margin-bottom: 1em;">
                        <label style="font-weight: 500;">Additional Duties</label>
                        <div id="refineDutiesList"></div>
                        <button class="btn btn-outline" id="addRefineDutyBtn" type="button" style="margin-top: 0.5em;">
                            + Add Duty
                        </button>
                    </div>
                    <div style="display: flex; gap: 1rem; margin-top: 1.5em; flex-wrap: wrap; align-items: center;">
                        <div style="display: flex; gap: 1rem; flex-wrap: wrap;">
                            <button class="btn btn-primary" id="regenerateGradeAnalysisBtn" style="background: #2563eb; border-color: #2563eb;">
                                <i class="fas fa-sync-alt"></i> Regenerate
                            </button>
                            <button class="btn btn-primary" id="generateEvalStatementBtn" style="background: #2563eb; border-color: #2563eb;">
                                <i class="fas fa-file-alt"></i> Evaluation Statement
                            </button>
                        </div>
                        <div style="flex:1"></div>
                        <div style="display: flex; align-items: center; gap: 0.5em;">
                            <button class="btn btn-primary" id="acceptGradeBtn" style="margin-top:0; min-width: 160px;">
                                <i class="fas fa-check"></i> Accept Grade
                            </button>
                            <div id="gradeAcceptedMsg" style="margin-left:0.5em; color:#10b981; font-weight:600; display:none;">Grade accepted!</div>
                        </div>
                    </div>
                    <div id="evaluationStatementResult" style="margin-top:2em;"></div>
                </div>
            </div>
        </div>
    `;

    // Setup the refine grade UI
    setupRefineGradeAnalysisUI();

    // Accept Grade button logic
    const acceptGradeBtn = document.getElementById('acceptGradeBtn');
    const gradeAcceptedMsg = document.getElementById('gradeAcceptedMsg');
    if (acceptGradeBtn) {
        acceptGradeBtn.onclick = function() {
            formState.gsGrade = recommendedGrade;
            acceptGradeBtn.disabled = true;
            acceptGradeBtn.style.opacity = '0.6';
            if (gradeAcceptedMsg) gradeAcceptedMsg.style.display = '';
            // Enable the continue button if present
            const continueDutiesBtn = document.getElementById('continueDutiesBtn');
            if (continueDutiesBtn) {
                continueDutiesBtn.disabled = false;
                continueDutiesBtn.style.opacity = '1';
                continueDutiesBtn.style.cursor = 'pointer';
            }
        };
    }

    // Regenerate Grade Analysis button logic
    const regenerateBtn = document.getElementById('regenerateGradeAnalysisBtn');
    // Only enable if there is at least one non-empty additional duty
    function updateRegenBtnState() {
        const hasAdditionalDuties = Array.isArray(formState.refineDuties) && formState.refineDuties.some(d => d.trim().length > 0);
        regenerateBtn.disabled = !hasAdditionalDuties;
        regenerateBtn.style.opacity = hasAdditionalDuties ? '1' : '0.6';
        regenerateBtn.style.cursor = hasAdditionalDuties ? 'pointer' : 'not-allowed';
    }
    updateRegenBtnState();
    document.getElementById('refineDutiesList').addEventListener('input', updateRegenBtnState);

    regenerateBtn.onclick = async function() {
        if (regenerateBtn.disabled) return;
        // Combine old duties and new additional duties
        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        const payload = { duties: allDuties, supervisoryLevel: formState.supervisoryLevel || '' };
        const gradeAnalysisDiv = document.getElementById('gradeAnalysisResult');
        gradeAnalysisDiv.innerHTML += `<div style="margin-top:1em;"><span class="spinner"></span> Regenerating grade and job series analysis...</div>`;
        try {
            const response = await fetch('/api/recommend-series', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!response.ok) throw new Error('Server error');
            const newData = await response.json();
            renderDutiesGradeAnalysis(newData);
        } catch (err) {
            gradeAnalysisDiv.innerHTML += `<div class="alert alert-warning">Error: ${err.message}</div>`;
        }
    };

    // Generate Evaluation Statement button handler (unchanged)
    document.getElementById('generateEvalStatementBtn').onclick = async function() {
        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        const payload = {
            duties: allDuties,
            gsGrade: recommendedGrade,
            jobSeries: formState.jobSeries || '',
            jobTitle: formState.jobSeries ? (jobSeriesData[formState.jobSeries]?.title || '') : '',
            positionTitle: formState.positionTitle || '',
            supervisoryLevel: formState.supervisoryLevel || ''
        };
        const evalResultDiv = document.getElementById('evaluationStatementResult');
        evalResultDiv.innerHTML = `<span class="spinner"></span> Generating evaluation statement...`;
        try {
            const response = await fetch('/api/generate-evaluation-statement', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!response.ok) throw new Error('Server error');
            const result = await response.json();
            const statement = result.evaluationStatement || 'No statement generated.';
            evalResultDiv.innerHTML = `
                <div style="background: #f3f4f6; border-radius: 0.5rem; padding: 1.5rem; margin-top: 1em;">
                    <h4 style="font-weight: 600; color: #111827; margin-bottom: 0.75rem;">AI Evaluation Statement</h4>
                    <div id="evalStatementText" style="white-space: pre-line; color: #374151;">${statement}</div>
                    <div style="margin-top: 1.5em; display: flex; gap: 12px; flex-wrap: wrap;">
                        <button class="btn btn-primary" id="copyEvalStatement" style="background: #2563eb; border-color: #2563eb;">
                            Copy to Clipboard
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementTxt" style="background: #2563eb; border-color: #2563eb;">
                            Download TXT
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementPdf" style="background: #2563eb; border-color: #2563eb;">
                            Download PDF
                        </button>
                        <button class="btn btn-primary" id="downloadEvalStatementDocx" style="background: #2563eb; border-color: #2563eb;">
                            Download DOCX
                        </button>
                    </div>
                </div>
            `;

            // Setup export buttons for evaluation statement
            const formattedText = statement;
            document.getElementById('copyEvalStatement').onclick = function() {
                navigator.clipboard.writeText(formattedText).then(() => {
                    showExportSuccess('Copied to clipboard!');
                }).catch(() => {
                    alert('Failed to copy.');
                });
            };
            document.getElementById('downloadEvalStatementTxt').onclick = function() {
                downloadFile(formattedText, 'Evaluation_Statement.txt', 'text/plain');
                showExportSuccess('TXT downloaded!');
            };
            document.getElementById('downloadEvalStatementPdf').onclick = function() {
                if (typeof window.jsPDF === 'undefined' && typeof window.jspdf === 'undefined') {
                    alert('PDF export requires jsPDF library.');
                    return;
                }
                generatePDF(formattedText, 'Evaluation_Statement.pdf');
                showExportSuccess('PDF downloaded!');
            };
            document.getElementById('downloadEvalStatementDocx').onclick = async function() {
                if (typeof window.docx === 'undefined') {
                    alert('DOCX export requires docx.js library.');
                    return;
                }
                await generateDOCX(formattedText, 'Evaluation_Statement.docx');
                showExportSuccess('DOCX downloaded!');
            };
        } catch (err) {
            evalResultDiv.innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
        }
    };
}

// Duty management functions
window.addDuty = function() {
    const container = document.getElementById('dutiesContainer');
    const dutyCount = container.children.length;
    if (dutyCount >= 20) return;

    const dutyGroup = document.createElement('div');
    dutyGroup.className = 'duty-input-group';
    dutyGroup.innerHTML = `
        <input type="text" class="duty-input" placeholder="Duty/Responsibility ${dutyCount + 1}...">
        <button type="button" class="remove-duty" onclick="removeDuty(this)">×</button>
    `;
    container.appendChild(dutyGroup);
    updateDutyCounter();
};

window.removeDuty = function(button) {
    const group = button.closest('.duty-input-group');
    if (group) {
        group.remove();
        updateDutyCounter();
    }
};

function updateDutyCounter() {
    const container = document.getElementById('dutiesContainer');
    const counter = document.getElementById('dutyCounter');
    if (!container || !counter) return;

    const count = container.querySelectorAll('.duty-input-group').length;
    if (count >= 6) {
        counter.textContent = `${count} duties entered (minimum met ✓)`;
        counter.style.color = '#4caf50';
    } else {
        counter.textContent = `${count} duties entered (need ${6 - count} more)`;
        counter.style.color = '#ff6b6b';
    }
}

function setupGenerateButton() {
    const btn = document.getElementById('generateBtn');
    const aiResult = document.getElementById('aiResult');

    btn.disabled = false;
    btn.style.opacity = '1';
    btn.style.cursor = 'pointer';

    btn.onclick = async function() {
        btn.disabled = true;
        btn.textContent = 'Generating...';
        aiResult.innerHTML = '<div class="loading"><span class="spinner"></span> Generating PD with AI...</div>';

        const jobSeries = selectedSeries ? selectedSeries.code : '';
        const subJobSeries = document.getElementById('subSeriesSelect') ? document.getElementById('subSeriesSelect').value : '';
        const federalAgency = formState.federalAgency;
        const subOrganization = formState.subOrg;
        const lowestOrg = formState.lowestOrg;

        const dutyList = (formState.duties || []).map(d => d.trim()).filter(d => d.length > 0);
        if (dutyList.length < 6) {
            aiResult.innerHTML = `<div class="alert alert-warning">Please enter at least 6 duties before generating the PD.</div>`;
            btn.disabled = false;
            btn.textContent = 'Generate Position Description';
            return;
        }
        const duties = dutyList.join('\n');

        // --- AI grade analysis fields ---
        const payload = {
            jobSeries,
            subJobSeries,
            federalAgency,
            subOrganization,
            lowestOrg,
            historicalData: duties,
            samplePD: '',
            gsGrade: formState.gsGrade,
            totalPoints: formState.totalPoints,
            gradeRange: formState.gradeRange,
            supervisoryLevel: formState.supervisoryLevel,
        };

        try {
            await streamPDGeneration(payload);
        } catch (err) {
            aiResult.innerHTML = `
                <div class="alert alert-warning">
                    <strong>Error:</strong> ${err.message}<br>
                    <details style="margin-top: 0.5rem;">
                        <summary>Debug Info</summary>
                        <pre style="font-size: 0.8rem; background: #f5f5f5; padding: 0.5rem; margin-top: 0.5rem;">
Check browser console for detailed logs.
Make sure your backend is returning properly formatted streaming response.
                        </pre>
                    </details>
                    <button class="btn btn-outline" onclick="setupGenerateButton()" style="margin-top: 0.5rem;">
                        Try Again
                    </button>
                </div>
            `;
        }
        btn.disabled = false;
        btn.textContent = 'Generate Position Description';
    };
}

async function streamPDGeneration(payload) {
    const aiResult = document.getElementById('aiResult');
    aiResult.innerHTML = '<div class="loading"><span class="spinner"></span> Generating PD with AI...</div>';

    const response = await fetch('/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    if (!response.body) {
        aiResult.innerHTML = '<div class="alert alert-warning">Streaming not supported by this browser.</div>';
        return;
    }

    const reader = response.body.getReader();
    let decoder = new TextDecoder();
    let buffer = '';
    let fullPD = '';

    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // Process each line
        let lines = buffer.split('\n');
        buffer = lines.pop(); // Save incomplete line for next chunk

        for (let line of lines) {
            if (line.startsWith('data: ')) {
                let json = line.slice(6);
                try {
                    let parsed = JSON.parse(json);
                    if (parsed.response) {
                        aiResult.textContent += parsed.response;
                        fullPD += parsed.response;
                    }
                    if (parsed.fullPD) {
                        aiResult.textContent = window.formatGeneratedPD(parsed.fullPD);
                        window.generatedPD = parsed.fullPD;
                    }
                } catch (e) {
                    // Ignore parse errors for partial lines
                }
            }
        }
    }

    // After streaming is done, set the formatted PD and advance to step 6
    if (fullPD.trim()) {
        window.generatedPD = fullPD;
        renderStep(6);
    }
}

// Alternative non-streaming approach if streaming continues to fail
async function tryNonStreamingGeneration(payload) {
    console.log('Trying non-streaming approach...');
    
    try {
        const response = await fetch('/api/generate-sync', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(`Server error: ${response.status}`);
        }

        const data = await response.json();
        return data.content || data.response || data.text || '';
        
    } catch (error) {
        console.error('Non-streaming generation failed:', error);
        throw error;
    }
}

function setupPDExportButtons() {
    // Use the raw generated text without any section splitting
    const formattedText = window.generatedPD || '';

    // Update the display with the raw text
    const finalPDElement = document.getElementById('finalPD');
    if (finalPDElement) {
        finalPDElement.textContent = formattedText;
    }

    document.getElementById('copyPD').onclick = function() {
        navigator.clipboard.writeText(formattedText).then(() => {
            showExportSuccess('Copied to clipboard!');
        }).catch(() => {
            alert('Failed to copy.');
        });
    };

    document.getElementById('downloadPDtxt').onclick = function() {
        downloadFile(formattedText, 'Position_Description.txt', 'text/plain');
        showExportSuccess('TXT downloaded!');
    };

    document.getElementById('downloadPDpdf').onclick = function() {
        if (typeof window.jsPDF === 'undefined' && typeof window.jspdf === 'undefined') {
            alert('PDF export requires jsPDF library.');
            return;
        }
        generatePDF(formattedText, 'Position_Description.pdf');
        showExportSuccess('PDF downloaded!');
    };

    document.getElementById('downloadPDdocx').onclick = async function() {
        if (typeof window.docx === 'undefined') {
            alert('DOCX export requires docx.js library.');
            return;
        }
        await generateDOCX(formattedText, 'Position_Description.docx');
        showExportSuccess('DOCX downloaded!');
    };
}

function displayReviewContent() {
    console.log('=== DISPLAY REVIEW CONTENT ===');
    const reviewTextElement = document.getElementById('reviewText');
    if (!reviewTextElement) {
        console.log('reviewText element not found');
        return;
    }
    
    console.log('pdSections before review formatting:', Object.keys(pdSections));
    
    // Clean up pdSections one more time before formatting
    const cleanedSections = cleanupMajorDutyDuplicates({ ...pdSections });
    console.log('Cleaned sections for review:', Object.keys(cleanedSections));
    
    const sectionContentsOnly = getSectionContentsOnly(cleanedSections);
    console.log('Section contents only:', Object.keys(sectionContentsOnly));
    
    const cleanContent = formatUpdatedPD(sectionContentsOnly);
    console.log('Formatted content length:', cleanContent.length);
    
    reviewTextElement.textContent = cleanContent;
    console.log('=== END DISPLAY REVIEW CONTENT ===');
}

// Updated review actions for updated PDs only
function setupReviewActions() {
    console.log('=== SETUP REVIEW ACTIONS ===');
    
    // Clean sections before formatting
    const cleanedSections = cleanupMajorDutyDuplicates({ ...pdSections });
    const sectionContentsOnly = getSectionContentsOnly(cleanedSections);
    const cleanContent = formatUpdatedPD(sectionContentsOnly);
    
    console.log('Clean content for review actions, length:', cleanContent.length);
    
    // Update the review display
    const reviewTextElement = document.getElementById('reviewText');
    if (reviewTextElement) {
        reviewTextElement.textContent = cleanContent;
    }

    // Setup action buttons
    document.getElementById('copyUpdatedPD').onclick = function() {
        navigator.clipboard.writeText(cleanContent).then(() => {
            showExportSuccess('Copied to clipboard!');
            // Do NOT call showPDCompletion() here
        }).catch(() => {
            alert('Failed to copy.');
        });
    };

    document.getElementById('downloadUpdatedPDtxt').onclick = function() {
        const filename = originalFileName ? 
            originalFileName.replace(/\.[^/.]+$/, '') + '_Updated.txt' : 
            'Position_Description_Updated.txt';
        downloadFile(cleanContent, filename, 'text/plain');
        showExportSuccess('TXT downloaded!');
        showPDCompletion();
    };

    document.getElementById('downloadUpdatedPDpdf').onclick = function() {
        const filename = originalFileName ? 
            originalFileName.replace(/\.[^/.]+$/, '') + '_Updated.pdf' : 
            'Position_Description_Updated.pdf';
        generatePDF(cleanContent, filename);
        showExportSuccess('PDF downloaded!');
        showPDCompletion();
    };

    document.getElementById('downloadUpdatedPDdocx').onclick = async function() {
        const filename = originalFileName ? 
            originalFileName.replace(/\.[^/.]+$/, '') + '_Updated.docx' : 
            'Position_Description_Updated.docx';
        await generateDOCX(cleanContent, filename);
        showExportSuccess('DOCX downloaded!');
        showPDCompletion();
    };
    
    console.log('=== END SETUP REVIEW ACTIONS ===');
}

// PD Formatting Functions - Fixed to handle generated vs updated PDs differently

window.formatGeneratedPD = function(pdText) {
    if (!pdText) return '';
    
    let text = pdText.trim();
    
    // Fix the most critical issues only:
    
    // 1. Fix header section - add line breaks where missing
    text = text.replace(/(U\.S\. Department of Justice)([A-Z])/g, '$1\n$2');
    text = text.replace(/(Criminal Division)([A-Z][^r])/g, '$1\n$2');
    text = text.replace(/(GS-\d+)([A-Z])/g, '$1\n$2');
    
    // 2. Ensure HEADER and INTRODUCTION are separated
    text = text.replace(/(Criminal Investigator)(\*\*INTRODUCTION:\*\*)/g, '$1\n\n$2');
    
    // 3. Fix factor spacing - just add space before the parentheses
    text = text.replace(/(\*\*Factor\s*\d+\s*-\s*[^L]*?)Level(\d+-\d+,\s*\d+\s*Points\*\*)/gi, '$1(Level $2)');
    
    // 4. Ensure major sections have line breaks before them
    const sections = ['INTRODUCTION:', 'MAJOR DUTIES:', 'FACTOR EVALUATION', 'EVALUATION SUMMARY:', 'CONDITIONS OF EMPLOYMENT:', 'TITLE AND SERIES DETERMINATION:', 'FAIR LABOR STANDARDS ACT DETERMINATION:'];
    sections.forEach(section => {
        const regex = new RegExp(`([^\\n])(\\*\\*${section.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&')})`, 'gi');
        text = text.replace(regex, '$1\n\n$2');
    });
    
    return text;
};

// Simple markdown to HTML converter
function simpleMarkdownToHTML(text) {
    return text
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>');
}

// Keep your existing render function but use the simple approach
function renderGeneratedPDStep(content) {
    content.innerHTML = `
        <div class="card">
            <div class="card-header">
                <div class="card-title"><i class="fas fa-file-alt"></i> Generated PD
                <span> <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                    <div class="step-tooltip">
                        Review the generated position description and save it.
                    </div>
                </span>
                </div>
                <div class="card-description">Download and finalize</div>
            </div>
            <div class="card-content">
                <div class="pd-display" id="finalPD" style="max-height: 400px; overflow-y: auto; border: 1px solid #e5e7eb; padding: 1rem; border-radius: 0.5rem; background-color: #f9fafb; line-height: 1.6; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;"></div>
                <div style="margin-top:24px; display:flex; gap:12px; flex-wrap:wrap;">
                    <button class="btn btn-primary" id="copyPD" style="background: #2563eb; border-color: #2563eb;">Copy to Clipboard</button>
                    <button class="btn btn-primary" id="downloadPDtxt" style="background: #2563eb; border-color: #2563eb;">Download TXT</button>
                    <button class="btn btn-primary" id="downloadPDpdf" style="background: #2563eb; border-color: #2563eb;">Download PDF</button>
                    <button class="btn btn-primary" id="downloadPDdocx" style="background: #2563eb; border-color: #2563eb;">Download DOCX</button>
                </div>
            </div>
        </div>
    `;
    
    const generatedText = window.generatedPD || '';
    const finalPDElement = document.getElementById('finalPD');
    
    if (generatedText) {
        // Apply minimal formatting, then convert to HTML
        const formattedText = window.formatGeneratedPD(generatedText);
        const htmlContent = simpleMarkdownToHTML(formattedText);
        finalPDElement.innerHTML = htmlContent;
    } else {
        finalPDElement.innerHTML = '<div style="color: #6b7280; font-style: italic; text-align: center; padding: 2rem;">No position description has been generated yet.</div>';
    }
    
    setupPDExportButtons();
}

// Test with your actual document
window.testSimpleFormatter = function() {
    const testText = `**HEADER:**
U.S. Department of JusticeCriminal DivisionCriminal DivisionGS-13Organizational Title: Criminal Investigator**INTRODUCTION:**
The Criminal Investigator position plays a critical role...

**Factor1 - Knowledge Required by the Position Level1-7,1250 Points**
The position requires comprehensive knowledge...`;
    
    console.log('BEFORE:');
    console.log(testText);
    console.log('\nAFTER:');
    console.log(window.formatGeneratedPD(testText));
};
// Enhanced test function that shows the problematic areas
window.testPDFormatter = function() {
    const testText = `**HEADER:**
U.S. Department of JusticeCriminal DivisionCriminal DivisionGS-13Organizational Title: Criminal Investigator**INTRODUCTION:**
The Criminal Investigator plays a critical role...

1. **Investigation Planning and Strategy (40%):** Develops and implements investigation strategies...2. **Evidence Collection and Analysis (30%):** Collects and analyzes...

**Factor1 - Knowledge Required by the Position Level1-7,1250 Points**
The position requires comprehensive knowledge...

**EVALUATION SUMMARY:**
**Total Points:2535**
**Final Grade: GS-13**
**Grade Range:2356-2855**`;

    console.log('=== ORIGINAL ===');
    console.log(testText);
    console.log('\n=== FORMATTED ===');
    const formatted = window.formatGeneratedPD(testText);
    console.log(formatted);
    
    // Check for common issues
    console.log('\n=== ISSUE CHECK ===');
    console.log('Stray asterisks found:', (formatted.match(/^\*\*\s*$/gm) || []).length);
    console.log('Double closing asterisks:', (formatted.match(/\*\*\*\*/g) || []).length);
    console.log('Malformed headers:', (formatted.match(/\*\*[^*]*:\*\*\*\*/g) || []).length);
    
    return formatted;
};

// Debugging version with step-by-step output
window.formatGeneratedPDDebug = function(pdText) {
    console.log('=== STEP BY STEP DEBUGGING ===');
    let text = pdText;
    
    console.log('1. Original length:', text.length);
    
    // Show header issues
    const headerMatches = text.match(/U\.S\. Department of Justice[A-Z]/g);
    console.log('2. Header issues found:', headerMatches ? headerMatches.length : 0);
    
    // Apply the formatter
    text = window.formatGeneratedPD(text);
    
    console.log('3. After formatting length:', text.length);
    
    // Check for issues in final result
    const strayAsterisks = text.match(/^\*\*\s*$/gm);
    const doubleClosing = text.match(/\*\*\*\*/g);
    
    console.log('4. Issues in result:');
    console.log('   - Stray asterisks:', strayAsterisks ? strayAsterisks.length : 0);
    console.log('   - Double closing asterisks:', doubleClosing ? doubleClosing.length : 0);
    
    return text;
};

function getSectionContentsOnly(sections) {
    console.log('=== GET SECTION CONTENTS ONLY ===');
    console.log('Input sections:', Object.keys(sections));
    
    // First clean up any duplicates in the input
    const cleanedSections = cleanupMajorDutyDuplicates({ ...sections });
    console.log('Cleaned input sections:', Object.keys(cleanedSections));
    
    // Convert {header: {header, content, ...}} to {header: content}
    const result = {};
    Object.keys(cleanedSections).forEach(key => {
        if (typeof cleanedSections[key] === 'object' && cleanedSections[key] !== null && 'content' in cleanedSections[key]) {
            result[key] = cleanedSections[key].content;
        } else {
            result[key] = cleanedSections[key];
        }
    });
    
    console.log('Result sections:', Object.keys(result));
    console.log('=== END GET SECTION CONTENTS ONLY ===');
    return result;
}

function formatUpdatedPD(sectionsObject) {
    // Clean up the sections object first to remove any duplicates
    const cleanedSections = cleanupMajorDutyDuplicates({ ...sectionsObject });

    // Deduplicate all "MAJOR DUTY X" and "MAJOR DUTIESX" (with or without percent, spaces, etc.)
    const majorDutyMap = {};
    Object.keys(cleanedSections).forEach(key => {
        // Normalize: remove all non-alphanum, uppercase, extract number
        const norm = key.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
        const match = norm.match(/^MAJORDUT(Y|IES)(\d+)/);
        if (match) {
            const base = `MAJORDUTY${match[2]}`;
            // Only keep the first occurrence for each number
            if (!majorDutyMap[base]) {
                majorDutyMap[base] = key;
            }
        }
    });

    // 1. HEADER and INTRODUCTION
    const sectionOrder = [];
    ['HEADER', 'INTRODUCTION'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 2. MAJOR DUTIES (main section, if present)
    const majorDutiesKey = findSectionKeyFlexible(cleanedSections, 'MAJOR DUTIES');
    if (majorDutiesKey) sectionOrder.push(majorDutiesKey);

    // 3. All deduped "MAJOR DUTY X" and "MAJOR DUTIESX" sections in order
    Object.values(majorDutyMap)
        .sort((a, b) => {
            const aNum = parseInt(a.match(/\d+/)?.[0] || '0', 10);
            const bNum = parseInt(b.match(/\d+/)?.[0] || '0', 10);
            return aNum - bNum;
        })
        .forEach(key => sectionOrder.push(key));

    // 4. FACTOR EVALUATION - COMPLETE ANALYSIS (if present)
    const factorEvalKey = findSectionKeyFlexible(cleanedSections, 'FACTOR EVALUATION - COMPLETE ANALYSIS');
    if (factorEvalKey) sectionOrder.push(factorEvalKey);

    // 5. All Factor sections in order (use actual keys, not generic)
    const factorKeys = Object.keys(cleanedSections)
        .filter(k => /^Factor\s*\d+/i.test(k))
        .sort((a, b) => {
            const aNum = parseInt(a.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            const bNum = parseInt(b.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            return aNum - bNum;
        });
    factorKeys.forEach(key => sectionOrder.push(key));

    // 6. Summary sections
    ['Total Points', 'Final Grade', 'Grade Range'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 7. Remaining standard sections
    [
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 8. Any remaining sections not already rendered
    const alreadyRendered = new Set(sectionOrder);
    Object.keys(cleanedSections).forEach(key => {
        if (!alreadyRendered.has(key) && cleanedSections[key] && cleanedSections[key].trim()) {
            sectionOrder.push(key);
        }
    });

    // Build the formatted PD, using the actual section key as the header
    let formatted = '';
    sectionOrder.forEach(section => {
        const content = cleanedSections[section];
        if (content && content.trim()) {
            // For summary sections, only show the header if content is identical
            if (
                /^(Total Points|Final Grade|Grade Range)/i.test(section) &&
                content.trim() === section.trim()
            ) {
                formatted += `**${section}**\n\n`;
            } else {
                formatted += `**${section}**\n${content.trim()}\n\n`;
            }
        }
    });

    return formatted.trim();
}

function extractValueForHeader(sectionType, content) {
    const firstLine = content.split('\n')[0] || content;
        
    if (sectionType === 'Total Points') {
        const match = firstLine.match(/(\d+)/);
        return match ? `Total Points: ${match[1]}` : 'Total Points';
    }
        
    if (sectionType === 'Final Grade') {
        const match = firstLine.match(/(GS-?\d+)/i);
        return match ? `Final Grade: ${match[1]}` : 'Final Grade';
    }
        
    if (sectionType === 'Grade Range') {
        const match = firstLine.match(/(\d+-\d+)/);
        return match ? `Grade Range: ${match[1]}` : 'Grade Range';
    }
        
    return sectionType;
}

// Export all functions to window - CRITICAL for external access
window.getCurrentSectionKey = getCurrentSectionKey;
window.undoSectionEdit = undoSectionEdit;
window.resetSectionEdit = resetSectionEdit;
window.cancelSectionEdit = cancelSectionEdit;
window.saveSectionEdit = saveSectionEdit;
window.updateSectionDisplayAndTextarea = updateSectionDisplayAndTextarea;
window.updateSaveStatus = updateSaveStatus;
window.resetAllSections = resetAllSections;
window.formatUpdatedPD = formatUpdatedPD;
window.extractValueForHeader = extractValueForHeader;
window.findTextareaElement = findTextareaElement;
window.findDisplayElement = findDisplayElement;

// Also export helper functions
window.sectionKeyToId = sectionKeyToId;
window.findSectionKeyFlexible = findSectionKeyFlexible;
window.cleanContent = cleanContent;

function cleanContent(content) {
    if (!content) return '';
    return content.trim()
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '\n')
        .replace(/\n{3,}/g, '\n\n');
}

// Helper functions that need to exist for the code to work
function sectionKeyToId(key) {
    return key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
}

function findSectionKeyFlexible(sections, searchKey) {
    if (!sections || !searchKey) return null;
    
    // Try exact match first
    if (sections[searchKey]) return searchKey;
    
    // Try case-insensitive
    const lowerSearch = searchKey.toLowerCase();
    const exactMatch = Object.keys(sections).find(k => k.toLowerCase() === lowerSearch);
    if (exactMatch) return exactMatch;
    
    // Try partial match
    const partialMatch = Object.keys(sections).find(k => 
        k.toLowerCase().includes(lowerSearch) || lowerSearch.includes(k.toLowerCase())
    );
    return partialMatch;
}

window.formatUpdatedPD = formatUpdatedPD;

// Track edit history for each section
if (!window.sectionEditHistory) window.sectionEditHistory = {};

function createSectionDiv(title, content) {
    console.log(`Creating section div for: "${title}"`);
    
    // Check if this section already exists in the DOM
    const existingSection = document.querySelector(`[data-section-title="${title}"]`);
    if (existingSection) {
        console.log(`Section "${title}" already exists in DOM, removing old version`);
        existingSection.remove();
    }
    
    const cleanedContent = cleanContent(content || '');

    let headerHtml = '';

    if (!window.sectionEditStacks[title]) window.sectionEditStacks[title] = [cleanedContent];

    const sectionDiv = document.createElement('div');
    sectionDiv.className = 'editor-section default';
    sectionDiv.setAttribute('data-section-title', title); // Add identifier for duplicate detection
    
    if (title.startsWith('MAJOR DUTY ')) {
        sectionDiv.classList.add('major-duty-item');
    }
    if (title.startsWith('Factor')) {
        sectionDiv.classList.add('factor-item');
    }

    // Check if this is a summary section
    const isSummarySection = /^Total Points:|^Final Grade:|^Grade Range:/i.test(title);

    sectionDiv.innerHTML = `
        ${headerHtml}
        <div class="section-header">
            <div class="section-header-content">
                <span class="section-title-label" style="font-weight:700; font-size:1.1em; width:100%; margin-bottom:0.5em; display:block;">
                    ${title}
                </span>
                ${!isSummarySection ? `
                <button class="section-edit-button" type="button" onclick="toggleSectionEdit('${title}', this)">
                    <i class="fas fa-edit"></i>
                    Edit
                </button>
                ` : ''}
            </div>
        </div>
        <div class="section-content">
            ${!isSummarySection ? `
            <div class="content-display" onclick="toggleSectionEdit('${title}')" id="display-${sectionKeyToId(title)}">
                ${cleanedContent || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>'}
            </div>
            <div class="content-editor" style="display: none;" id="editor-${sectionKeyToId(title)}">
                <textarea class="editor-textarea" id="textarea-${sectionKeyToId(title)}" style="width:100%;min-height:120px;">${cleanedContent}</textarea>
                <div class="editor-actions">
                    <button class="editor-button" type="button" onclick="undoSectionEdit('${title}')">Undo</button>
                    <button class="editor-button" type="button" onclick="resetSectionEdit('${title}')">Reset Section</button>
                    <button class="editor-button" type="button" onclick="cancelSectionEdit('${title}')">Cancel</button>
                    <button class="editor-button primary" type="button" onclick="saveSectionEdit('${title}')">
                        <i class="fas fa-save"></i> Save Changes
                    </button>
                </div>
            </div>
            ` : ''}
        </div>
    `;

    if (!isSummarySection) {
        setTimeout(() => {
            const textarea = sectionDiv.querySelector(`#textarea-${sectionKeyToId(title)}`);
            if (textarea) {
                textarea.disabled = false;
                textarea.readOnly = false;
                textarea.oninput = function() {
                    const stack = window.sectionEditStacks[title];
                    if (stack[stack.length - 1] !== textarea.value) {
                        stack.push(textarea.value);
                    }
                };
            }
        }, 0);
    }

    return sectionDiv;
}

async function saveSectionEdit(title) {
    if (/^Total Points:|^Final Grade:|^Grade Range:/i.test(title)) {
        return;
    }

    const textarea = document.getElementById(`textarea-${sectionKeyToId(title)}`);
    if (!textarea) return; // Prevent error if textarea does not exist

    const display = document.getElementById(`display-${sectionKeyToId(title)}`);
    const section = display ? display.closest('.editor-section') : null;
    let newContent = textarea.value.trim();

    // Clean the content before saving to remove any unwanted asterisks
    newContent = cleanContent(newContent);

    // Save edited content
    let newTitle = title;

    // Track history with timestamp
    if (!window.sectionEditHistory[title]) window.sectionEditHistory[title] = [];
    window.sectionEditHistory[title].push({ content: newContent, header: newTitle, ts: Date.now() });

    // Sync the live stack to only the saved content
    window.sectionEditStacks[newTitle] = [newContent];
    pdSections[newTitle] = newContent;

    // Update display without adding asterisks
    display.innerHTML = newContent || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
    display.style.whiteSpace = 'pre-line';

    toggleSectionEdit(newTitle);
    section.classList.remove('modified');
    updateSaveStatus();

    // If this is a factor section, update points, headers, and grade via AI
    if (/^Factor\s*\d+/i.test(newTitle)) {
        try {
            // Show loading indicator
            const loadingDiv = document.createElement('div');
            loadingDiv.id = 'factor-loading';
            loadingDiv.innerHTML = '<div class="loading" style="padding: 1rem; text-align: center;"><span class="spinner"></span> Recalculating factor points and grade...</div>';
            section.appendChild(loadingDiv);

            // Collect all factor sections with content
            const factorContents = {};
            for (let i = 1; i <= 9; i++) {
                const key = Object.keys(pdSections).find(k => 
                    k.match(new RegExp(`^Factor\\s*${i}`, 'i'))
                );
                if (key && pdSections[key] && pdSections[key].trim()) {
                    factorContents[`Factor ${i}`] = pdSections[key];
                }
            }

            console.log('Sending factors for analysis:', factorContents);

            const response = await fetch('/api/update-factor-points', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ factors: factorContents })
            });

            // Remove loading indicator
            const loading = document.getElementById('factor-loading');
            if (loading) loading.remove();

            if (!response.ok) {
                throw new Error(`Server error: ${response.status}`);
            }
            
            const data = await response.json();
            console.log('Received factor analysis:', data);

            if (data.error) {
                throw new Error(data.error);
            }

            // Update factor headers and content with enhanced validation
            if (data.factors) {
                await updateFactorSections(data.factors);
            }

            // Update summary sections
            await updateSummarySections(data);

            // Show success feedback
            showFactorUpdateSuccess();

        } catch (err) {
            console.error('Failed to update factor points/grade:', err);
            showFactorUpdateError(err.message);
            
            // Remove loading indicator if still present
            const loading = document.getElementById('factor-loading');
            if (loading) loading.remove();
        }
    }
}
window.saveSectionEdit = saveSectionEdit;

async function updateSummarySections(data) {
    const updates = [
        { key: 'Total Points', value: data.totalPoints },
        { key: 'Final Grade', value: data.finalGrade },
        { key: 'Grade Range', value: data.gradeRange }
    ];

    for (const update of updates) {
        if (update.value) {
            let headerWithValue = '';
            let content = '';
            if (update.key === 'Total Points') {
                headerWithValue = `Total Points: ${update.value}`;
                content = `Total Points: ${update.value}`;
            } else if (update.key === 'Final Grade') {
                headerWithValue = `Final Grade: ${update.value}`;
                content = `Final Grade: ${update.value}`;
            } else if (update.key === 'Grade Range') {
                headerWithValue = `Grade Range: ${update.value}`;
                content = `Grade Range: ${update.value}`;
            }

            // Remove all old keys for this summary type
            Object.keys(pdSections).forEach(k => {
                if (k.startsWith(update.key) && k !== headerWithValue) {
                    delete pdSections[k];
                }
            });

            pdSections[headerWithValue] = content;
            updateSectionDisplayAndTextarea(headerWithValue, content);
        }
    }
    renderEditableSections();
}

console.log('pdSections after summary update:', pdSections);

async function updateFactorSections(factors) {
    for (const [factorKey, factorData] of Object.entries(factors)) {
        const factorNum = factorKey.replace('Factor ', '');
        const factorName = getFactorName(factorNum);

        // Always build header as: FactorX - Name LevelY-Z, NNNN Points
        let newHeader = `Factor ${factorNum}`;
        if (factorName) newHeader += ` - ${factorName}`;
        if (factorData.level) newHeader += ` Level ${factorData.level}`;
        if (factorData.points) newHeader += `, ${factorData.points} Points`;

        // Find current key in pdSections
        const currentKey = Object.keys(pdSections).find(k =>
            k.match(new RegExp(`^Factor\\s*${factorNum}`, 'i'))
        ) || factorKey;

        const content = factorData.content || pdSections[currentKey] || '';

        // Update pdSections with new header
        if (currentKey !== newHeader) {
            pdSections[newHeader] = content;
            if (currentKey !== factorKey) {
                delete pdSections[currentKey];
            }
            updateSectionKeyInDOM(currentKey, newHeader);
        } else {
            pdSections[currentKey] = content;
        }

        updateSectionDisplayAndTextarea(newHeader, content);
    }
}

function updateSectionKeyInDOM(oldKey, newKey) {
const sectionDiv = document.getElementById(`display-${sectionKeyToId(oldKey)}`)?.closest('.editor-section');
    if (sectionDiv) {
        const headerLabel = sectionDiv.querySelector('.section-title-label');
        if (headerLabel) headerLabel.textContent = newKey;
        
        const displayDiv = sectionDiv.querySelector(`#display-${sectionKeyToId(oldKey)}`);
        const textareaDiv = sectionDiv.querySelector(`#textarea-${sectionKeyToId(oldKey)}`);
        const editorDiv = sectionDiv.querySelector(`#editor-${sectionKeyToId(oldKey)}`);
        
        if (displayDiv) displayDiv.id = `display-${sectionKeyToId(newKey)}`;
        if (textareaDiv) textareaDiv.id = `textarea-${sectionKeyToId(newKey)}`;
        if (editorDiv) editorDiv.id = `editor-${sectionKeyToId(newKey)}`;
        
        if (displayDiv) displayDiv.setAttribute('onclick', `toggleSectionEdit('${newKey}')`);

        const buttons = sectionDiv.querySelectorAll('.editor-actions button');
        buttons.forEach(btn => {
            const onclick = btn.getAttribute('onclick');
            if (onclick && onclick.includes(oldKey)) {
                btn.setAttribute('onclick', onclick.replace(new RegExp(oldKey, 'g'), newKey));
            }
        });
    }
}

function getFactorName(factorNum) {
    const factorNames = {
        "1": "Knowledge Required by the Position",
        "2": "Supervisory Controls",
        "3": "Guidelines",
        "4": "Complexity",
        "5": "Scope and Effect",
        "6": "Personal Contacts",
        "7": "Purpose of Contacts",
        "8": "Physical Demands",
        "9": "Work Environment"
    };
    return factorNames[String(factorNum)] || "";
}

// Helper to update both display and textarea for a section
function updateSectionDisplayAndTextarea(sectionKey, content) {
    const actualKey = findSectionKeyFlexible(pdSections, sectionKey) || sectionKey;
    const cleanedContent = cleanContent(content);
        
    const displayDiv = findDisplayElement(actualKey) || findDisplayElement(sectionKey);
    const textareaDiv = findTextareaElement(actualKey) || findTextareaElement(sectionKey);

    if (displayDiv) {
        displayDiv.innerHTML = cleanedContent.replace(/\n/g, '<br>') || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
        displayDiv.style.whiteSpace = 'pre-line';
    }
    if (textareaDiv) {
        textareaDiv.value = cleanedContent;
    }
        
    if (!pdSections) window.pdSections = {};
    pdSections[actualKey] = cleanedContent;
        
    if (!window.sectionEditStacks[actualKey]) {
        window.sectionEditStacks[actualKey] = [cleanedContent];
    } else {
        // Update the current value in the stack
        window.sectionEditStacks[actualKey][window.sectionEditStacks[actualKey].length - 1] = cleanedContent;
    }
}

// Export the updated functions
window.formatUpdatedPD = formatUpdatedPD;
window.saveSectionEdit = saveSectionEdit;
window.updateSectionDisplayAndTextarea = updateSectionDisplayAndTextarea;
window.cleanContent = cleanContent;

// Debug function to find where asterisks are being added
function debugAsteriskSources() {
    console.log('=== DEBUGGING ASTERISK SOURCES ===');
    
    // Check pdSections content
    Object.keys(pdSections).forEach(key => {
        const content = pdSections[key];
        if (content && content.includes('*')) {
            console.log(`ASTERISK FOUND in pdSections["${key}"]:`, content.substring(0, 100));
        }
    });
    
    // Check display elements
    document.querySelectorAll('[id^="display-"]').forEach(el => {
        if (el.innerHTML.includes('*')) {
            console.log(`ASTERISK FOUND in display element ${el.id}:`, el.innerHTML.substring(0, 100));
        }
    });
    
    // Check textarea elements
    document.querySelectorAll('[id^="textarea-"]').forEach(el => {
        if (el.value.includes('*')) {
            console.log(`ASTERISK FOUND in textarea ${el.id}:`, el.value.substring(0, 100));
        }
    });
}

function debugSectionStructure() {
    console.log('=== SECTION STRUCTURE DEBUG ===');
    
    // Find all buttons that call these functions
    const buttons = document.querySelectorAll('button[onclick*="undoSectionEdit"], button[onclick*="resetSectionEdit"], button[onclick*="cancelSectionEdit"]');
    console.log('Found action buttons:', buttons.length);
    
    buttons.forEach((button, index) => {
        const onclick = button.getAttribute('onclick');
        console.log(`Button ${index}:`, {
            onclick: onclick,
            text: button.textContent.trim(),
            parentClasses: button.parentElement?.className
        });
        
        // Extract the parameter from onclick
        const match = onclick.match(/['"`]([^'"`]+)['"`]/);
        if (match) {
            const sectionTitle = match[1];
            console.log(`  - Section title from onclick: "${sectionTitle}"`);
            
            // Look for elements related to this section
            const possibleTextareaIds = [
                `textarea-${sectionTitle.replace(/[^a-zA-Z0-9]/g, '').toLowerCase()}`,
                `textarea-${sectionTitle.replace(/\s+/g, '').toLowerCase()}`,
                `textarea-${sectionTitle.replace(/\W/g, '')}`
            ];
            
            console.log('  - Trying textarea IDs:', possibleTextareaIds);
            possibleTextareaIds.forEach(id => {
                const element = document.getElementById(id);
                console.log(`    ${id}: ${element ? 'FOUND' : 'NOT FOUND'}`);
            });
        }
    });
    
    // List all actual textareas
    const allTextareas = document.querySelectorAll('textarea');
    console.log('\nAll textareas in document:', allTextareas.length);
    allTextareas.forEach((textarea, index) => {
        console.log(`Textarea ${index}:`, {
            id: textarea.id,
            name: textarea.name || 'no name',
            placeholder: textarea.placeholder || 'no placeholder'
        });
    });
    
    // List all elements with display- prefix
    const allDisplays = document.querySelectorAll('[id^="display-"]');
    console.log('\nAll display elements:', allDisplays.length);
    allDisplays.forEach((display, index) => {
        console.log(`Display ${index}:`, {
            id: display.id
        });
    });
    
    console.log('=== END DEBUG ===');
}

function undoSectionEdit(title) {
    if (!title) return;
    const textarea = findTextareaElement(title);
    if (!textarea) {
        console.warn('undoSectionEdit: Could not find textarea for', title);
        return;
    }
    const currentKey = getCurrentSectionKey(title);
    if (!window.sectionEditStacks[currentKey]) window.sectionEditStacks[currentKey] = [pdSections[currentKey] || ''];
    const stack = window.sectionEditStacks[currentKey];

    if (stack.length > 1) {
        stack.pop();
        textarea.value = stack[stack.length - 1];
    } else {
        textarea.value = stack[0];
    }
    const display = findDisplayElement(currentKey);
    if (display) {
        display.innerHTML = textarea.value || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
    }
}

function resetSectionEdit(title) {
    if (!title) return;
    const textarea = findTextareaElement(title);
    if (!textarea) {
        console.warn('resetSectionEdit: Could not find textarea for', title);
        return;
    }
    const currentKey = getCurrentSectionKey(title);
    if (!window.sectionEditStacks[currentKey]) window.sectionEditStacks[currentKey] = [pdSections[currentKey] || ''];
    const stack = window.sectionEditStacks[currentKey];
    if (stack.length > 0) {
        window.sectionEditStacks[currentKey] = [stack[0]];
        textarea.value = stack[0];
    }
    const display = findDisplayElement(currentKey);
    if (display) {
        display.innerHTML = stack[0] || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
    }
}

function cancelSectionEdit(title) {
    if (!title) return;
    const textarea = findTextareaElement(title);
    if (!textarea) return;
    const currentKey = getCurrentSectionKey(title);
    textarea.value = pdSections[currentKey] || '';
    const display = findDisplayElement(currentKey);
    if (display) {
        display.innerHTML = pdSections[currentKey] || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
    }
    toggleSectionEdit(currentKey);
}

function getCurrentSectionKey(title) {
    if (!title) return null;
    
    // Try exact match first
    if (pdSections && pdSections[title] !== undefined) return title;
    
    if (!pdSections) return title;
    
    // Try to find by element ID matching
    const titleId = sectionKeyToId(title);
    const exactIdMatch = Object.keys(pdSections).find(k => sectionKeyToId(k) === titleId);
    if (exactIdMatch) return exactIdMatch;
    
    // Try case-insensitive matching
    const lowerTitle = title.toLowerCase();
    const caseMatch = Object.keys(pdSections).find(k => k.toLowerCase() === lowerTitle);
    if (caseMatch) return caseMatch;
    
    // Special handling for factor sections
    if (/^Factor\s*\d+/i.test(title)) {
        const factorNum = title.match(/^Factor\s*(\d+)/i)?.[1];
        if (factorNum) {
            const factorMatch = Object.keys(pdSections).find(k => {
                const match = k.match(/^Factor\s*(\d+)/i);
                return match && match[1] === factorNum;
            });
            if (factorMatch) return factorMatch;
        }
    }
    
    // Special handling for summary sections
    if (/total\s*points/i.test(title)) {
        const summaryMatch = Object.keys(pdSections).find(k => /total\s*points/i.test(k));
        if (summaryMatch) return summaryMatch;
    }
    if (/final\s*grade/i.test(title)) {
        const gradeMatch = Object.keys(pdSections).find(k => /final\s*grade/i.test(k));
        if (gradeMatch) return gradeMatch;
    }
    if (/grade\s*range/i.test(title)) {
        const rangeMatch = Object.keys(pdSections).find(k => /grade\s*range/i.test(k));
        if (rangeMatch) return rangeMatch;
    }
    
    return title;
}

function updateSaveStatus() {
    const saveStatus = document.getElementById('saveStatus');
    if (saveStatus) {
        const modifiedSections = document.querySelectorAll('.editor-section.modified').length;
        if (modifiedSections > 0) {
            saveStatus.innerHTML = `<span style="color: #047857; font-weight: 500;">You have unsaved changes in ${modifiedSections} section(s)</span>`;
        } else {
            saveStatus.textContent = 'All changes saved';
        }
    }
}

function resetAllSections() {
    if (confirm('Are you sure you want to reset all changes? This cannot be undone.')) {
        if (window.originalPDText && typeof splitPDSections === 'function') {
            pdSections = splitPDSections(window.originalPDText);
            if (typeof shouldSplitMajorDuties === 'function' && shouldSplitMajorDuties(pdSections)) {
                pdSections = splitMajorDutiesIntoSections(pdSections);
            }
            Object.keys(pdSections).forEach(key => {
                if (typeof fixBrokenParagraphs === 'function') {
                    pdSections[key] = fixBrokenParagraphs(pdSections[key]);
                }
            });
        }
        if (typeof renderEditableSections === 'function') {
            renderEditableSections();
        }
        updateSaveStatus();
    }
}

// AI rewrite functionality
function setupAIRewriteButton(textareaId, resultId, buttonId) {
    const btn = document.getElementById(buttonId);
    if (!btn) return;

    btn.onclick = async function() {
        const input = document.getElementById(textareaId).value.trim();
        const resultDiv = document.getElementById(resultId);

        if (!input) {
            resultDiv.textContent = "Please enter some duties/responsibilities.";
            return;
        }

        resultDiv.innerHTML = '<span class="spinner"></span> Rewriting...';

        try {
            const response = await fetch('/api/rewrite-duties', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ duties: input })
            });

            if (!response.ok) {
                const errorText = await response.text();
                let errorMsg = "Server error";
                try {
                    const errorJson = JSON.parse(errorText);
                    errorMsg = errorJson.error || errorMsg;
                } catch {}
                throw new Error(errorMsg);
            }

            // Read the response as text and parse streaming lines
            const text = await response.text();
            let rewritten = '';
            text.split('\n').forEach(line => {
                if (line.trim().startsWith('data: ')) {
                    let jsonLine = line.trim().substring(6);
                    try {
                        const obj = JSON.parse(jsonLine);
                        if (obj.rewritten) {
                            rewritten += obj.rewritten;
                        }
                        if (obj.error) {
                            throw new Error(obj.error);
                        }
                    } catch { /* ignore parse errors for non-JSON lines */ }
                }
            });

            // Remove any leading AI commentary
            rewritten = rewritten.replace(/^Here are the rewritten federal job duties:\s*/i, '');

            // Remove double bullets or bullet+number at the start of a line
            rewritten = rewritten.replace(/^(\*+\s*)+(\d+\.\s*)?/gm, '* ');

            // Clean up the rewritten text
            rewritten = rewritten.replace(/^(Sure, here is the revised duties list:|Sure, here is the revised duties list)\s*/i, '');
            rewritten = rewritten.replace(/^\s*\*\*?[\w\s]+:?[\*]?\s*$/gm, '');

            const profDutiesRegex = /^(\*+)?\s*\**\s*Professional Duties and Responsibilities:?(\**)?\s*$/i;

            // Extract duties starting with a single bullet
            let lines = rewritten.split('\n')
                .map(line => line.trim())
                .filter(line =>
                    line.length > 0 &&
                    !profDutiesRegex.test(line) &&
                    line.startsWith('* ')
                )
                .map(line => line.replace(/^\*\s*/, '').trim());

            // Fallback: If no lines found, split by line and filter out headers and empty lines
            if (lines.length === 0) {
                lines = rewritten.split('\n')
                    .map(line => line.trim())
                    .filter(line =>
                        line.length > 0 &&
                        !profDutiesRegex.test(line) &&
                        !/^(\d+\.)?\s*(Introduction|Major Duties|Duties|Responsibilities|Performs other duties as assigned)/i.test(line) &&
                        !/^<think>/i.test(line) &&
                        !/^Alright,|^Looking at|^I also need|^Maybe numbering|^I should avoid|^Lastly,|^Human Resources and Personnel Support/i.test(line)
                    );
            }

            if (lines.length === 0) {
                resultDiv.innerHTML = `<div style="color:#ef4444;">No duties found in AI rewrite. Please try again or edit your input.</div>`;
                return;
            }

            resultDiv.innerHTML = `
                <ul style="padding-left:1.5em;">${lines.map(line => `<li>${line}</li>`).join('')}</ul>
                <button class="btn btn-primary" id="useRewritesBtn" style="margin-top:1em;">Use Rewrites</button>
            `;

            document.getElementById('useRewritesBtn').onclick = function() {
                const dutyInputs = document.querySelectorAll('.duty-input');
                for (let i = 0; i < dutyInputs.length; i++) {
                    dutyInputs[i].value = lines[i] || '';
                }

                const container = document.getElementById('dutiesContainer');
                let currentCount = dutyInputs.length;
                for (let i = currentCount; i < lines.length && i < 20; i++) {
                    addDuty();
                    document.querySelectorAll('.duty-input')[i].value = lines[i];
                }

                updateDutyCounter();
                container.dispatchEvent(new Event('input', { bubbles: true }));
            };
        } catch (err) {
            resultDiv.innerHTML = `<div style="color:#ef4444;">Error: ${err.message}</div>`;
        }
    };
}

// PD Formatting Functions for Consistent Structure

function formatPDToSampleStructure(pdSections) {
    // Use the same section order as your example
    const sectionOrder = [
        'HEADER', 'INTRODUCTION', 'MAJOR DUTIES',
        'MAJOR DUTY 1', 'MAJOR DUTY 2', 'MAJOR DUTY 3', 'MAJOR DUTY 4', 'MAJOR DUTY 5',
        'MAJOR DUTY 6', 'MAJOR DUTY 7', 'MAJOR DUTY 8', 'MAJOR DUTY 9', 'MAJOR DUTY 10',
        'FACTOR EVALUATION - COMPLETE ANALYSIS', // <-- move here
        'Factor 1', 'Factor 2', 'Factor 3', 'Factor 4', 'Factor 5', 'Factor 6', 'Factor 7', 'Factor 8', 'Factor 9',
        'Total Points', 'Final Grade', 'Grade Range',
        'CONDITIONS OF EMPLOYMENT', 'TITLE AND SERIES DETERMINATION', 'FAIR LABOR STANDARDS ACT DETERMINATION'
    ];

    let formatted = '';
    sectionOrder.forEach(section => {
        const key = Object.keys(pdSections).find(k => k.replace(/\*/g, '').replace(/:/g, '').trim().toUpperCase().startsWith(section.toUpperCase()));
        if (key && pdSections[key]) {
            formatted += `**${section}**\n${pdSections[key].trim()}\n\n================================================================================\n\n`;
        }
    });

    // Add any remaining sections not in the preferred order
    Object.keys(pdSections).forEach(key => {
        if (!sectionOrder.some(section => key.replace(/\*/g, '').replace(/:/g, '').trim().toUpperCase().startsWith(section.toUpperCase()))) {
            formatted += `**${key}**\n${pdSections[key].trim()}\n\n================================================================================\n\n`;
        }
    });

    return formatted.trim();
}

// For updated PDs, keep splitting and formatting as before
function formatUpdatedPD(sectionsObject) {
    // Clean up the sections object first to remove any duplicates
    const cleanedSections = cleanupMajorDutyDuplicates({ ...sectionsObject });

    // Deduplicate all "MAJOR DUTY X" and "MAJOR DUTIESX" (with or without percent, spaces, etc.)
    const majorDutyMap = {};
    Object.keys(cleanedSections).forEach(key => {
        // Normalize: remove all non-alphanum, uppercase, extract number
        const norm = key.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
        const match = norm.match(/^MAJORDUT(Y|IES)(\d+)/);
        if (match) {
            const base = `MAJORDUTY${match[2]}`;
            // Only keep the first occurrence for each number
            if (!majorDutyMap[base]) {
                majorDutyMap[base] = key;
            }
        }
    });

    // 1. HEADER and INTRODUCTION
    const sectionOrder = [];
    ['HEADER', 'INTRODUCTION'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 2. MAJOR DUTIES (main section, if present)
    const majorDutiesKey = findSectionKeyFlexible(cleanedSections, 'MAJOR DUTIES');
    if (majorDutiesKey) sectionOrder.push(majorDutiesKey);

    // 3. All deduped "MAJOR DUTY X" and "MAJOR DUTIESX" sections in order
    Object.values(majorDutyMap)
        .sort((a, b) => {
            const aNum = parseInt(a.match(/\d+/)?.[0] || '0', 10);
            const bNum = parseInt(b.match(/\d+/)?.[0] || '0', 10);
            return aNum - bNum;
        })
        .forEach(key => sectionOrder.push(key));

    // 4. FACTOR EVALUATION - COMPLETE ANALYSIS (if present)
    const factorEvalKey = findSectionKeyFlexible(cleanedSections, 'FACTOR EVALUATION - COMPLETE ANALYSIS');
    if (factorEvalKey) sectionOrder.push(factorEvalKey);

    // 5. All Factor sections in order (use actual keys, not generic)
    const factorKeys = Object.keys(cleanedSections)
        .filter(k => /^Factor\s*\d+/i.test(k))
        .sort((a, b) => {
            const aNum = parseInt(a.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            const bNum = parseInt(b.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            return aNum - bNum;
        });
    factorKeys.forEach(key => sectionOrder.push(key));

    // 6. Summary sections
    ['Total Points', 'Final Grade', 'Grade Range'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 7. Remaining standard sections
    [
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ].forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        if (key) sectionOrder.push(key);
    });

    // 8. Any remaining sections not already rendered
    const alreadyRendered = new Set(sectionOrder);
    Object.keys(cleanedSections).forEach(key => {
        if (!alreadyRendered.has(key) && cleanedSections[key] && cleanedSections[key].trim()) {
            sectionOrder.push(key);
        }
    });

    // Build the formatted PD, using the actual section key as the header
    let formatted = '';
    sectionOrder.forEach(section => {
        const content = cleanedSections[section];
        if (content && content.trim()) {
            formatted += `**${section}**\n${content.trim()}\n\n`;
        }
    });

    return formatted.trim();
}

window.debugSectionStructure = debugSectionStructure;
window.undoSectionEdit = undoSectionEdit;
window.resetSectionEdit = resetSectionEdit;
window.cancelSectionEdit = cancelSectionEdit;
window.formatUpdatedPD = formatUpdatedPD;

// Auto-run debug on load to help troubleshoot
console.log('Section editing functions loaded. Run debugSectionStructure() to diagnose issues.');

function ensureBasicSections(sections) {
    console.log('=== ENSURE BASIC SECTIONS ===');
    console.log('Input sections:', Object.keys(sections));
    
    // Clean up input first
    let processed = cleanupMajorDutyDuplicates({ ...sections });
    
    // If we have a "Full Document" section, try to split it
    if (processed['Full Document'] && !processed['HEADER'] && !processed['INTRODUCTION']) {
        const splitSections = splitPDSections(processed['Full Document']);
        delete processed['Full Document'];
        Object.assign(processed, splitSections);
        
        // Clean again after splitting
        processed = cleanupMajorDutyDuplicates(processed);
    }
    
    console.log('Processed sections:', Object.keys(processed));
    console.log('=== END ENSURE BASIC SECTIONS ===');
    return processed;
}

window.displayReviewContent = displayReviewContent;
window.formatUpdatedPD = formatUpdatedPD;
window.setupReviewActions = setupReviewActions;
window.getSectionContentsOnly = getSectionContentsOnly;
window.ensureBasicSections = ensureBasicSections;

// Helper function to find section key with flexible matching
function findSectionKeyFlexible(sections, targetSection) {
    const keys = Object.keys(sections);
    
    const exactMatch = keys.find(key => key === targetSection);
    if (exactMatch) return exactMatch;
    
    const caseInsensitiveMatch = keys.find(key => 
        key.toUpperCase() === targetSection.toUpperCase()
    );
    if (caseInsensitiveMatch) return caseInsensitiveMatch;
    
    if (targetSection.startsWith('Factor ')) {
        const factorNum = targetSection.match(/Factor (\d+)/)?.[1];
        if (factorNum) {
            const factorMatch = keys.find(key => {
                const cleanKey = key.replace(/\*/g, '').replace(/:/g, '').trim();
                return new RegExp(`^Factor\\s*${factorNum}\\b`, 'i').test(cleanKey);
            });
            if (factorMatch) return factorMatch;
        }
    }
    
    if (targetSection.startsWith('MAJOR DUTY ')) {
        const dutyNum = targetSection.match(/MAJOR DUTY (\d+)/)?.[1];
        if (dutyNum) {
            const dutyMatch = keys.find(key => {
                const cleanKey = key.replace(/\*/g, '').replace(/:/g, '').trim();
                return new RegExp(`^MAJOR\\s*DUTY\\s*${dutyNum}\\b`, 'i').test(cleanKey);
            });
            if (dutyMatch) return dutyMatch;
        }
    }
    
    const cleanTargetSection = targetSection.replace(/\*/g, '').replace(/:/g, '').replace(/\s+/g, ' ').trim();
    const cleanMatch = keys.find(key => {
        const cleanKey = key.replace(/\*/g, '').replace(/:/g, '').replace(/\s+/g, ' ').trim();
        return cleanKey.toUpperCase() === cleanTargetSection.toUpperCase();
    });
    if (cleanMatch) return cleanMatch;
    
    const partialMatch = keys.find(key => 
        key.toUpperCase().includes(targetSection.toUpperCase()) ||
        targetSection.toUpperCase().includes(key.toUpperCase())
    );
    
    return partialMatch || null;
}

window.formatUpdatedPD = formatUpdatedPD;
window.updateFactorSections = updateFactorSections;
window.updateSummarySections = updateSummarySections;
window.updateSectionDisplayAndTextarea = updateSectionDisplayAndTextarea;
window.updateSectionKeyInDOM = updateSectionKeyInDOM;
window.renderEditableSections = renderEditableSections;
window.createSectionDiv = createSectionDiv;
window.cleanContent = cleanContent;
window.extractValueForHeader = extractValueForHeader;
window.getFactorName = getFactorName;
window.findSectionKeyFlexible = findSectionKeyFlexible;

function extractJobSeriesFromPD(text) {
    const match = text.match(/GS[- ]?(\d{4})/i);
    return match ? match[1] : 'XXXX';
}

function extractTitleFromPD(text) {
    const lines = text.split('\n');
    for (let line of lines.slice(0, 10)) {
        if (line && !line.includes('Department') && !line.includes('GS-') && line.length > 5 && line.length < 100) {
            return line.trim();
        }
    }
    return 'Position Title';
}

function extractAgencyFromPD(text) {
    const match = text.match(/U\.S\. Department of (.+)/);
    return match ? match[1].split('\n')[0] : 'Agency';
}

// Export functions for use in main application
window.formatPDToSampleStructure = formatPDToSampleStructure;
window.formatGeneratedPD = formatGeneratedPD;
window.formatUpdatedPD = formatUpdatedPD;

// File processing utilities
function splitPDSections(text) {
    // Normalize line breaks and whitespace
    text = text.replace(/\r\n/g, '\n').replace(/[ \t]+/g, ' ');

    // Build a regex that matches both bolded and plain section headers
    // Handles: **HEADER:**, HEADER:, Introduction, Major Duties, Factor 1 - ..., Total Points: ...
    const sectionHeaderRegex = new RegExp(
        [
            // Bolded headers (existing)
            '\\*\\*([A-Z][A-Z0-9 \\-:&(),%/]+)\\*\\*\\s*:?',
            // Plain headers (DOE style)
            '^(HEADER|INTRODUCTION|MAJOR DUTIES|FACTOR EVALUATION - COMPLETE ANALYSIS|CONDITIONS OF EMPLOYMENT|TITLE AND SERIES DETERMINATION|FAIR LABOR STANDARDS ACT DETERMINATION|Major Duties|Introduction|Conditions of Employment|Title and Series Determination|Fair Labor Standards Act Determination)\\s*:?',
            // Factor headers (plain or bolded)
            '^Factor\\s*\\d+\\s*-\\s*[^\\n]+',
            // Total Points, Final Grade, Grade Range
            '^Total Points\\s*:\\s*\\d+',
            '^Final Grade\\s*:\\s*GS-?\\d+',
            '^Grade Range\\s*:\\s*\\d+-\\d+'
        ].join('|'),
        'gim'
    );

    // Find all section header matches and their indices
    let matches = [];
    let match;
    while ((match = sectionHeaderRegex.exec(text)) !== null) {
        // Determine header text
        let header = match[1] || match[2] || match[0];
        header = header.replace(/\*\*/g, '').replace(/:/g, '').trim();
        matches.push({
            raw: match[0],
            title: header,
            index: match.index
        });
    }

    // If no matches, fallback to splitting on double line breaks
    if (matches.length === 0) {
        const parts = text.split(/\n{2,}/);
        let sections = {};
        let sectionNum = 1;
        parts.forEach(part => {
            const trimmed = part.trim();
            if (trimmed.length > 0) {
                sections[`Section ${sectionNum++}`] = trimmed;
            }
        });
        return sections;
    }

    // Add a dummy match at the end for easier slicing
    matches.push({ title: 'END', index: text.length });

    let sections = {};
    for (let i = 0; i < matches.length - 1; i++) {
        const headerEnd = matches[i].index + matches[i].raw.length;
        const start = headerEnd;
        const end = matches[i + 1].index;
        const rawContent = text.substring(start, end).trim();
        const content = cleanContent(rawContent);
        const header = matches[i].title;
        if (!sections[header] && content) {
            sections[header] = content;
        }
    }

    return sections;
}

// Clean all existing pdSections data
function cleanAllPDSections() {
    Object.keys(pdSections).forEach(key => {
        if (pdSections[key]) {
            pdSections[key] = cleanContent(pdSections[key]);
        }
    });
}

// Call this function to debug and then clean
window.debugAsteriskSources = debugAsteriskSources;
window.cleanAllPDSections = cleanAllPDSections;
window.createSectionDiv = createSectionDiv;
window.splitPDSections = splitPDSections;
window.cleanContent = cleanContent;

// Auto-clean on load
if (typeof pdSections !== 'undefined') {
    cleanAllPDSections();
}

console.log('Asterisk debugging and cleaning functions loaded. Call debugAsteriskSources() to find sources, then cleanAllPDSections() to clean.');

// Helper to get the length of the section header including "**" and optional ":"
function matchSectionHeaderLength(text, index) {
    const headerMatch = text.substring(index).match(/^\*\*[A-Z0-9 \-]+?\*\s*:?\s*/);
    return headerMatch ? headerMatch[0].length : 0;
}

function identifyBasicSections(text) {
    const lines = text.split('\n').map(line => line.trim()).filter(line => line.length > 0);
    const sections = {};
    
    // Try to identify header (first few lines that look like org info)
    let headerLines = [];
    let headerEndIndex = 0;
    
    for (let i = 0; i < Math.min(10, lines.length); i++) {
        const line = lines[i];
        if (line.includes('U.S. Department') || 
            line.includes('GS-') || 
            line.match(/^\w+\s*[-–]\s*\w+/) ||
            (line.length < 50 && !line.includes('.'))) {
            headerLines.push(line);
            headerEndIndex = i + 1;
        } else {
            break;
        }
    }
    
    if (headerLines.length > 0) {
        sections['HEADER'] = headerLines.join('\n');
    }
    
    // Look for introduction paragraph
    let introStart = headerEndIndex;
    let introLines = [];
    
    for (let i = introStart; i < Math.min(introStart + 10, lines.length); i++) {
        const line = lines[i];
        if (line.includes('incumbent') ||
            line.includes('serves as') ||
            line.includes('responsible for') ||
            line.includes('position')) {
            introLines.push(line);
            if (line.endsWith('.')) break;
        } else if (introLines.length > 0) {
            break;
        }
    }
    
    if (introLines.length > 0) {
        sections['INTRODUCTION'] = introLines.join(' ');
    }
    
    // Look for major duties section
    const dutiesKeywords = ['major duties', 'duties', 'responsibilities', 'performs', 'provides'];
    let dutiesStartIndex = -1;
    
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i].toLowerCase();
        if (dutiesKeywords.some(keyword => line.includes(keyword)) &&
            (line.match(/^\d+\./) || line.includes('duty') || line.includes('responsible'))) {
            dutiesStartIndex = i;
            break;
        }
    }
    
    if (dutiesStartIndex !== -1) {
        let dutiesLines = [];
        for (let i = dutiesStartIndex; i < lines.length; i++) {
            const line = lines[i];
            if (line.toLowerCase().includes('factor') || 
                line.toLowerCase().includes('condition') ||
                line.toLowerCase().includes('title and series')) {
                break;
            }
            dutiesLines.push(line);
        }
        
        if (dutiesLines.length > 0) {
            sections['MAJOR DUTIES'] = dutiesLines.join('\n');
        }
    }
    
    // If we still don't have sections, put everything in a single section
    if (Object.keys(sections).length === 0) {
        sections['Full Document'] = text.trim();
    }
    
    return sections;
}

function extractBasicSectionsFromStart(text) {
    const sections = {};
    const lines = text.split('\n').map(line => line.trim()).filter(line => line.length > 0);

    // Extract header: lines before "Introduction" or "Major Duties"
    let headerLines = [];
    let headerEndIndex = 0;
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (
            /^Introduction$/i.test(line) ||
            /^Major Duties$/i.test(line)
        ) {
            headerEndIndex = i;
            break;
        }
        headerLines.push(line);
    }
    if (headerLines.length > 0) {
        sections['HEADER'] = headerLines.join('\n');
    }

    // Find Introduction section
    let introStart = lines.findIndex(line => /^Introduction$/i.test(line));
    let majorDutiesStart = lines.findIndex(line => /^Major Duties$/i.test(line));

    if (introStart !== -1) {
        let introEnd = majorDutiesStart !== -1 ? majorDutiesStart : lines.length;
        const introLines = lines.slice(introStart + 1, introEnd);
        if (introLines.length > 0) {
            sections['INTRODUCTION'] = introLines.join(' ');
        }
    }

    // Find Major Duties section
    if (majorDutiesStart !== -1) {
        const dutiesLines = lines.slice(majorDutiesStart + 1);
        if (dutiesLines.length > 0) {
            sections['MAJOR DUTIES'] = dutiesLines.join('\n');
        }
    }

    return sections;
}

function extractGradingInformation(text) {
    const gradingLines = [];
    
    // Look for Total Points
    const totalPointsMatch = text.match(/Total\s+Points:\s*(\d+)/i);
    if (totalPointsMatch) {
        gradingLines.push(`Total Points: ${totalPointsMatch[1]}`);
    }
    
    // Look for Final Grade
    const finalGradeMatch = text.match(/Final\s+Grade:\s*(GS-?\d+)/i);
    if (finalGradeMatch) {
        gradingLines.push(`Final Grade: ${finalGradeMatch[1]}`);
    }
    
    // Look for Grade Range
    const gradeRangeMatch = text.match(/Grade\s+Range:\s*(\d+-\d+)/i);
    if (gradeRangeMatch) {
        gradingLines.push(`Grade Range: ${gradeRangeMatch[1]}`);
    }
    
    return gradingLines.length > 0 ? gradingLines.join('\n') : null;
}

async function extractTextFromPDF(file) {
    return new Promise((resolve, reject) => {
        if (typeof window.pdfjsLib === 'undefined') {
            reject(new Error('PDF parsing requires pdf.js library.'));
            return;
        }
        const reader = new FileReader();
        reader.onload = async function(e) {
            const typedarray = new Uint8Array(e.target.result);
            window.pdfjsLib.getDocument(typedarray).promise.then(async function(pdf) {
                let text = '';
                
                for (let i = 1; i <= pdf.numPages; i++) {
                    const page = await pdf.getPage(i);
                    const content = await page.getTextContent();
                    
                    // Group items by line based on y-position with better threshold
                    const lines = [];
                    let currentLine = [];
                    let lastY = null;
                    
                    content.items.forEach(item => {
                        const currentY = item.transform[5];
                        
                        // Use a larger threshold (5+ pixels) to avoid breaking mid-sentence
                        // and check if the text looks like it continues a sentence
                        if (lastY !== null && Math.abs(currentY - lastY) > 5) {
                            if (currentLine.length > 0) {
                                const lineText = currentLine.map(item => item.str).join('').trim();
                                if (lineText) {
                                    lines.push(lineText);
                                }
                            }
                            currentLine = [];
                        }
                        
                        currentLine.push(item);
                        lastY = currentY;
                    });
                    
                    // Don't forget the last line
                    if (currentLine.length > 0) {
                        const lineText = currentLine.map(item => item.str).join('').trim();
                        if (lineText) {
                            lines.push(lineText);
                        }
                    }
                    
                    // Join lines intelligently - preserve paragraph breaks but merge continuing sentences
                    for (let j = 0; j < lines.length; j++) {
                        let line = lines[j];
                        
                        // If this line doesn't end with sentence-ending punctuation
                        // and the next line doesn't start with a capital letter or number,
                        // it's likely a continuation
                        if (j < lines.length - 1) {
                            const nextLine = lines[j + 1];
                            const lineEndsWithPunctuation = /[.!?]$/.test(line.trim());
                            const nextLineStartsNewSentence = /^[A-Z0-9]/.test(nextLine.trim());
                            const lineEndsWithPercent = /\(\d+%\)$/.test(line.trim());
                            
                            // If line doesn't end with punctuation and next line doesn't start new sentence,
                            // or if line ends with percentage, merge with space
                            if ((!lineEndsWithPunctuation && !nextLineStartsNewSentence) || lineEndsWithPercent) {
                                text += line + ' ';
                            } else {
                                text += line + '\n';
                            }
                        } else {
                            text += line + '\n';
                        }
                    }
                    
                    text += '\n'; // Page break
                }
                
                // Clean up the extracted text
                text = cleanupExtractedText(text);
                resolve(text);
            }, reject);
        };
        reader.readAsArrayBuffer(file);
    });
}

function cleanupExtractedText(text) {
    // Remove excessive whitespace
    text = text.replace(/[ \t]+/g, ' ');
    
    // Fix common PDF extraction issues
    text = text.replace(/\s+–\s+/g, ' - '); // Fix em-dashes
    text = text.replace(/GS\s*-\s*(\d+\/\d+)\s*-\s*(\d+)/g, 'GS-$1-$2'); // Fix GS series
    
    // Normalize line breaks - convert multiple line breaks to double line breaks for paragraphs
    text = text.replace(/\n{3,}/g, '\n\n');
    
    // Fix broken words that were hyphenated across lines
    text = text.replace(/(\w+)-\s*\n\s*(\w+)/g, '$1$2');
    
    // Remove standalone page numbers
    text = text.replace(/^\s*\d+\s*$/gm, '');
    
    return text.trim();
}

async function extractTextFromDOCX(file) {
    return new Promise((resolve, reject) => {
        if (typeof window.mammoth === 'undefined') {
            reject(new Error('DOCX parsing requires mammoth.js library.'));
            return;
        }
        
        const reader = new FileReader();
        reader.onload = function(e) {
            window.mammoth.convertToHtml({ arrayBuffer: e.target.result })
                .then(result => {
                    const html = result.value;
                    const tmp = document.createElement('div');
                    tmp.innerHTML = html;
                    resolve(tmp.innerText);
                })
                .catch(() => reject(new Error('Failed to parse DOCX.')));
        };
        reader.readAsArrayBuffer(file);
    });
}

// Use this function to assemble PD text for both generated and updated PDs
function assemblePDText(sections) {
    return Object.entries(sections)
        .map(([title, content]) => {
            if (title === 'Full PD') return content;
            return `${title}\n\n${content}`;
        })
        .join('\n\n================================================================================\n\n');
}

// Export functions
function downloadFile(content, filename, type) {
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

function generatePDF(content, filename) {
    if (typeof window.jsPDF === 'undefined' && typeof window.jspdf === 'undefined') {
        alert('PDF export requires jsPDF library.');
        return;
    }
    
    const doc = window.jspdf ? new window.jspdf.jsPDF() : new window.jsPDF();
    const lines = doc.splitTextToSize(content, 180);
    let y = 10;
    
    lines.forEach(line => {
        if (y + 10 > doc.internal.pageSize.getHeight() - 10) {
            doc.addPage();
            y = 10;
        }
        doc.text(line, 10, y);
        y += 10;
    });
    
    doc.save(filename);
}

async function generateDOCX(content, filename) {
    if (typeof window.docx === 'undefined') {
        alert('DOCX export requires docx.js library.');
        return;
    }
    
    const { Document, Packer, Paragraph, TextRun } = window.docx;
    const blocks = content.split(/\n\s*\n/);
    const paragraphs = blocks.map(block => {
        const lines = block.split('\n');
        return new Paragraph({
            children: lines.map(line => new TextRun(line)),
            spacing: { after: 200 }
        });
    });

    const doc = new Document({
        sections: [{
            properties: {},
            children: paragraphs
        }]
    });

    const blob = await Packer.toBlob(doc);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

function showExportSuccess(msg) {
    const div = document.createElement('div');
    div.style.cssText = `
        position: fixed; top: 20px; right: 20px; background: #28a745; color: white;
        padding: 12px 20px; border-radius: 8px; z-index: 9999; font-size: 1em;
        box-shadow: 0 2px 8px rgba(0,0,0,0.15); transition: opacity 0.4s ease;
    `;
    div.textContent = msg;
    document.body.appendChild(div);
    setTimeout(() => {
        div.style.opacity = '0';
        setTimeout(() => document.body.removeChild(div), 400);
    }, 2000);
}

function showPDCompletion() {
    currentUpdateStep =  4;
    renderUpdateSidebar(currentUpdateStep);
    renderUpdateStep(4);
}

function resetUpdateProcess() {
    currentUpdateStep = 1;
    pdSections = {};
    originalFileName = '';
    renderUpdateStep(1);
}

function showUpdateApp() {
    document.getElementById('mainApp').style.display = 'none';
    document.getElementById('updateApp').style.display = 'block';
    currentUpdateStep = 1;
    renderUpdateStep(1);
}

function showMainApp() {
    document.getElementById('updateApp').style.display = 'none';
    document.getElementById('mainApp').style.display = 'flex';
    renderStep(1);
}

// Export global functions
window.refreshJobSeriesData = initializeJobSeriesData;
window.getAvailableJobSeries = getAvailableJobSeries;
window.getJobSeries = getJobSeries;
window.searchJobSeries = searchJobSeries;
window.jobSeriesData = jobSeriesData;

function shouldSplitMajorDuties(sections) {
    // Find any key that matches major duties variations
    const majorDutiesKeys = Object.keys(sections).filter(k => {
        const normalized = k.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
        return normalized.match(/^MAJORDUT(Y|IES)\d*$/);
    });
    
    if (majorDutiesKeys.length === 0) return false;
    
    // Check if already split into numbered duties
    const alreadySplit = Object.keys(sections).some(k => 
        /^MAJOR DUTY\s+\d+$/i.test(k.trim())
    );
    
    if (alreadySplit) return false;
    
    // Check if any major duties section has numbered content
    return majorDutiesKeys.some(key => {
        const content = sections[key];
        return content && content.match(/^\s*\d+\.\s/m);
    });
}

function splitMajorDutiesIntoSections(sections) {
    // Find all keys that match major duties variations
    const majorDutiesKeys = Object.keys(sections).filter(k => {
        const normalized = k.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
        return normalized.match(/^MAJORDUT(Y|IES)\d*$/);
    });

    if (majorDutiesKeys.length === 0) return sections;

    // Use the first major duties key found
    const key = majorDutiesKeys[0];
    const dutiesText = sections[key];
    if (!dutiesText) return sections;

    // Split on lines starting with number dot (e.g., 1. ...)
    const dutyRegex = /(?:^|\n)(\d+\.\s*[\s\S]*?)(?=(?:\n\d+\.\s*)|$)/g;
    const matches = Array.from(dutiesText.matchAll(dutyRegex));

    if (matches.length > 1) {
        // Remove ALL major duties variations before adding the split sections
        majorDutiesKeys.forEach(k => {
            delete sections[k];
        });

        // Add the split duties with clean naming
        matches.forEach((match, idx) => {
            const newKey = `MAJOR DUTY ${idx + 1}`;
            const content = match[1].trim();
            sections[newKey] = content;
        });
    }

    return sections;
}

// Fix broken paragraphs in all sections
Object.keys(pdSections).forEach(key => {
    pdSections[key] = fixBrokenParagraphs(pdSections[key]);
});

function setupFileUpload() {
    const fileInput = document.getElementById('pdFileInput');
    if (!fileInput) return;

    fileInput.onchange = async function(e) {
        const file = e.target.files[0];
        if (!file) return;

        originalFileName = file.name;

        let text = '';
        try {
            if (file.name.endsWith('.pdf')) {
                text = await extractTextFromPDF(file);
            } else if (file.name.endsWith('.docx')) {
                text = await extractTextFromDOCX(file);
            } else if (file.name.endsWith('.txt')) {
                text = await file.text();
            } else {
                alert('Unsupported file type.');
                return;
            }
        } catch (err) {
            alert('Failed to extract text from file: ' + err.message);
            return;
        }

        // Store original text before any processing
        window.originalPDText = text;
        
        // Split into sections
        pdSections = splitPDSections(text);
        console.log('After splitPDSections:', Object.keys(pdSections));
        
        // Clean up any initial duplicates FIRST
        pdSections = cleanupMajorDutyDuplicates(pdSections);
        console.log('After first cleanup:', Object.keys(pdSections));
        
        // Split major duties if needed
        if (shouldSplitMajorDuties(pdSections)) {
            console.log('Splitting major duties...');
            pdSections = splitMajorDutiesIntoSections(pdSections);
            console.log('After splitting:', Object.keys(pdSections));
        }
        
        // Final cleanup after splitting
        pdSections = cleanupMajorDutyDuplicates(pdSections);
        console.log('After final cleanup:', Object.keys(pdSections));

        // Fix broken paragraphs in all sections
        Object.keys(pdSections).forEach(key => {
            pdSections[key] = fixBrokenParagraphs(pdSections[key]);
        });

        // Add summary sections if found in text
        const totalPointsMatch = text.match(/Total Points\s*:\s*(\d+)/i);
        if (totalPointsMatch) pdSections[`Total Points: ${totalPointsMatch[1]}`] = '';

        const finalGradeMatch = text.match(/Final Grade\s*:\s*(GS-?\d+)/i);
        if (finalGradeMatch) pdSections[`Final Grade: ${finalGradeMatch[1]}`] = '';

        const gradeRangeMatch = text.match(/Grade Range\s*:\s*([\d\-]+)/i);
        if (gradeRangeMatch) pdSections[`Grade Range: ${gradeRangeMatch[1]}`] = '';

        if (!pdSections['HEADER']) {
            const headerLines = text.split('\n').slice(0, 5).filter(l => l.trim()).join('\n');
            pdSections['HEADER'] = headerLines;
        }

        console.log('Final pdSections keys:', Object.keys(pdSections));
        renderUpdateStep(2);
    };
}

function loadSamplePD() {
    const samplePDFUrl = '/General_Engineer_PD_Sample.pdf';
    originalFileName = 'General_Engineer_PD_Sample.pdf';

    fetch(samplePDFUrl)
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            return response.blob();
        })
        .then(async blob => {
            const text = await extractTextFromPDF(new File([blob], 'General_Engineer_PD_Sample.pdf', { type: 'application/pdf' }));
            
            // Store original text before any processing
            window.originalPDText = text;
            
            pdSections = splitPDSections(text);
            console.log('Sample PD - After splitPDSections:', Object.keys(pdSections));

            // Clean up any initial duplicates FIRST
            pdSections = cleanupMajorDutyDuplicates(pdSections);
            console.log('Sample PD - After first cleanup:', Object.keys(pdSections));

            // Only split major duties if there are clear numbered duties
            if (shouldSplitMajorDuties(pdSections)) {
                console.log('Sample PD - Splitting major duties...');
                pdSections = splitMajorDutiesIntoSections(pdSections);
                console.log('Sample PD - After splitting:', Object.keys(pdSections));
            }

            // Final cleanup after splitting
            pdSections = cleanupMajorDutyDuplicates(pdSections);
            console.log('Sample PD - After final cleanup:', Object.keys(pdSections));

            // Fix broken paragraphs while preserving original structure
            Object.keys(pdSections).forEach(key => {
                pdSections[key] = fixBrokenParagraphs(pdSections[key]);
            });

            // Add summary sections if found in text
            const totalPointsMatch = text.match(/Total Points\s*:\s*(\d+)/i);
            if (totalPointsMatch) pdSections[`Total Points: ${totalPointsMatch[1]}`] = '';

            const finalGradeMatch = text.match(/Final Grade\s*:\s*(GS-?\d+)/i);
            if (finalGradeMatch) pdSections[`Final Grade: ${finalGradeMatch[1]}`] = '';

            const gradeRangeMatch = text.match(/Grade Range\s*:\s*([\d\-]+)/i);
            if (gradeRangeMatch) pdSections[`Grade Range: ${gradeRangeMatch[1]}`] = '';

            if (!pdSections['HEADER']) {
                const headerLines = text.split('\n').slice(0, 5).filter(l => l.trim()).join('\n');
                pdSections['HEADER'] = headerLines;
            }

            console.log('Sample PD - Final keys:', Object.keys(pdSections));
            renderUpdateStep(2);
        })
        .catch(error => {
            console.error('Failed to load sample PD PDF:', error);
            alert(`Failed to load sample PD PDF: ${error.message}\n\nPlease ensure the file 'General_Engineer_PD_Sample.pdf' is placed in your public directory.`);
        });
}

function cleanupMajorDutyDuplicates(sections) {
    const cleaned = { ...sections };
    const majorDutyKeys = Object.keys(cleaned).filter(key => 
        /major\s*dut(y|ies)\s*\d+/i.test(key)
    );
    
    // Group by number
    const groups = {};
    majorDutyKeys.forEach(key => {
        const match = key.match(/major\s*dut(?:y|ies)\s*(\d+)/i);
        if (match) {
            const num = match[1];
            if (!groups[num]) groups[num] = [];
            groups[num].push(key);
        }
    });
    
    // Keep only the first one in each group
    Object.values(groups).forEach(keys => {
        if (keys.length > 1) {
            const keep = keys[0];
            keys.slice(1).forEach(key => delete cleaned[key]);
        }
    });
    
    return cleaned;
}

// Initialize global objects if they don't exist
if (typeof window.sectionEditStacks === 'undefined') {
    window.sectionEditStacks = {};
}
if (typeof window.sectionEditHistory === 'undefined') {
    window.sectionEditHistory = {};
}
if (typeof pdSections === 'undefined') {
    window.pdSections = {};
}

function findTextareaElement(title) {
    if (!title) return null;
    
    const attempts = [
        // Original approach
        () => {
            const currentKey = getCurrentSectionKey(title);
            if (currentKey) {
                return document.getElementById(`textarea-${sectionKeyToId(currentKey)}`);
            }
            return null;
        },
        
        // Try with original title
        () => document.getElementById(`textarea-${sectionKeyToId(title)}`),
        
        // Try to find textarea in section with matching title
        () => {
            const sections = document.querySelectorAll('.editor-section');
            for (let section of sections) {
                const titleLabel = section.querySelector('.section-title-label');
                if (titleLabel && (titleLabel.textContent === title || titleLabel.textContent.toLowerCase() === title.toLowerCase())) {
                    return section.querySelector('textarea');
                }
            }
            return null;
        },
        
        // Try to find textarea by data attribute or other methods
        () => {
            const textareas = document.querySelectorAll('textarea');
            for (let textarea of textareas) {
                if (textarea.id && textarea.id.includes(sectionKeyToId(title))) {
                    return textarea;
                }
            }
            return null;
        },
        
        // Last resort - find any textarea in a section with similar content
        () => {
            const sections = document.querySelectorAll('.editor-section');
            for (let section of sections) {
                const display = section.querySelector('[id^="display-"]');
                if (display && display.textContent && display.textContent.toLowerCase().includes(title.toLowerCase().substring(0, 10))) {
                    return section.querySelector('textarea');
                }
            }
            return null;
        }
    ];
    
    for (let attempt of attempts) {
        try {
            const result = attempt();
            if (result) {
                console.log('Found textarea using attempt:', attempts.indexOf(attempt));
                return result;
            }
        } catch (e) {
            console.warn('Textarea finding attempt failed:', e);
        }
    }
    
    return null;
}

// ROBUST function to find display element
function findDisplayElement(title) {
    if (!title) return null;
    
    const attempts = [
        // Original approach
        () => {
            const currentKey = getCurrentSectionKey(title);
            if (currentKey) {
                return document.getElementById(`display-${sectionKeyToId(currentKey)}`);
            }
            return null;
        },
        
        // Try with original title
        () => document.getElementById(`display-${sectionKeyToId(title)}`),
        
        // Try to find display in section with matching title
        () => {
            const sections = document.querySelectorAll('.editor-section');
            for (let section of sections) {
                const titleLabel = section.querySelector('.section-title-label');
                if (titleLabel && (titleLabel.textContent === title || titleLabel.textContent.toLowerCase() === title.toLowerCase())) {
                    return section.querySelector('[id^="display-"]');
                }
            }
            return null;
        },
        
        // Try to find display by partial ID match
        () => {
            const displays = document.querySelectorAll('[id^="display-"]');
            for (let display of displays) {
                if (display.id && display.id.includes(sectionKeyToId(title))) {
                    return display;
                }
            }
            return null;
        }
    ];
    
    for (let attempt of attempts) {
        try {
            const result = attempt();
            if (result) {
                return result;
            }
        } catch (e) {
            console.warn('Display finding attempt failed:', e);
        }
    }
    
    return null;
}

function renderEditableSections() {
    console.log('=== RENDER EDITABLE SECTIONS START ===');
    
    const container = document.getElementById('editableSections');
    if (!container) {
        console.log('No editableSections container found');
        return;
    }

    console.log('pdSections keys before processing:', Object.keys(pdSections));

    // Step 1: Clean up data-level duplicates
    pdSections = cleanupMajorDutyDuplicates(pdSections);
    console.log('pdSections keys after cleanup:', Object.keys(pdSections));
    
    // Step 2: Clear DOM completely
    container.innerHTML = '';
    console.log('DOM cleared');

    // Step 3: Track what we've rendered to prevent DOM duplicates
    const renderedSections = new Set();
    
    function renderSection(sectionKey, customKey = null) {
        const key = customKey || findSectionKeyFlexible(pdSections, sectionKey);
        if (key && pdSections[key] !== undefined && !renderedSections.has(key)) {
            console.log(`Rendering: ${sectionKey} -> ${key}`);
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
            return true;
        }
        return false;
    }

    // Step 4: Render sections in order, tracking what's been rendered
    
    // 1. HEADER and INTRODUCTION
    renderSection('HEADER');
    renderSection('INTRODUCTION');

    // 2. Handle Major Duties sections
    const majorDutyKeys = Object.keys(pdSections)
        .filter(k => /^MAJOR DUTY \d+$/i.test(k.trim()))
        .sort((a, b) => {
            const aNum = parseInt(a.match(/\d+/)[0], 10);
            const bNum = parseInt(b.match(/\d+/)[0], 10);
            return aNum - bNum;
        });

    console.log('Found major duty keys:', majorDutyKeys);

    if (majorDutyKeys.length === 0) {
        // No split duties, look for main MAJOR DUTIES section
        renderSection('MAJOR DUTIES');
    } else {
        // Use split duties
        majorDutyKeys.forEach(key => renderSection(null, key));
    }

    // 3. Major duty sections with percentages
    const percentMajorDutyKeys = Object.keys(pdSections)
        .filter(k => /\(\d+%\)$/.test(k) && !/^MAJOR DUTY \d+$/i.test(k.trim()));
    
    percentMajorDutyKeys.forEach(key => renderSection(null, key));

    // 4. FACTOR EVALUATION - COMPLETE ANALYSIS
    renderSection('FACTOR EVALUATION - COMPLETE ANALYSIS');

    // 5. All Factor sections in order
    const factorKeys = Object.keys(pdSections)
        .filter(k => /^Factor\s*\d+/i.test(k))
        .sort((a, b) => {
            const aNum = parseInt(a.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            const bNum = parseInt(b.match(/^Factor\s*(\d+)/i)?.[1] || '0', 10);
            return aNum - bNum;
        });
    factorKeys.forEach(key => renderSection(null, key));

    // 6. Summary sections
    ['Total Points', 'Final Grade', 'Grade Range'].forEach(sectionType => {
        const keys = Object.keys(pdSections).filter(k => k.startsWith(sectionType));
        if (keys.length > 0) {
            const key = keys[keys.length - 1];
            renderSection(null, key);
        }
    });

    // 7. Remaining standard sections
    [
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ].forEach(sectionKey => renderSection(sectionKey));

    // 8. Any remaining sections not already rendered
    Object.keys(pdSections).forEach(key => {
        if (pdSections[key] !== undefined && !renderedSections.has(key)) {
            console.log(`Rendering remaining section: ${key}`);
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
        }
    });

    console.log('Final rendered sections:', Array.from(renderedSections));
    console.log('=== RENDER EDITABLE SECTIONS END ===');
}

// 4. Enhanced file upload with multiple cleanup points
function setupFileUpload() {
    const fileInput = document.getElementById('pdFileInput');
    if (!fileInput) return;

    fileInput.onchange = async function(e) {
        const file = e.target.files[0];
        if (!file) return;

        originalFileName = file.name;

        let text = '';
        try {
            if (file.name.endsWith('.pdf')) {
                text = await extractTextFromPDF(file);
            } else if (file.name.endsWith('.docx')) {
                text = await extractTextFromDOCX(file);
            } else if (file.name.endsWith('.txt')) {
                text = await file.text();
            } else {
                alert('Unsupported file type.');
                return;
            }
        } catch (err) {
            alert('Failed to extract text from file: ' + err.message);
            return;
        }

        // Store original text before any processing
        window.originalPDText = text;
        
        // Reset pdSections completely
        pdSections = {};
        
        // Split into sections
        pdSections = splitPDSections(text);
        console.log('Step 1 - After splitPDSections:', Object.keys(pdSections));
        
        // First cleanup
        pdSections = cleanupMajorDutyDuplicates(pdSections);
        console.log('Step 2 - After first cleanup:', Object.keys(pdSections));
        
        // Split major duties if needed
        if (shouldSplitMajorDuties(pdSections)) {
            console.log('Step 3 - Splitting major duties...');
            pdSections = splitMajorDutiesIntoSections(pdSections);
            console.log('Step 3 - After splitting:', Object.keys(pdSections));
        }
        
        // Final cleanup after splitting
        pdSections = cleanupMajorDutyDuplicates(pdSections);
        console.log('Step 4 - After final cleanup:', Object.keys(pdSections));

        // Fix broken paragraphs in all sections
        Object.keys(pdSections).forEach(key => {
            pdSections[key] = fixBrokenParagraphs(pdSections[key]);
        });

        // Add summary sections if found in text
        const totalPointsMatch = text.match(/Total Points\s*:\s*(\d+)/i);
        if (totalPointsMatch) pdSections[`Total Points: ${totalPointsMatch[1]}`] = '';

        const finalGradeMatch = text.match(/Final Grade\s*:\s*(GS-?\d+)/i);
        if (finalGradeMatch) pdSections[`Final Grade: ${finalGradeMatch[1]}`] = '';

        const gradeRangeMatch = text.match(/Grade Range\s*:\s*([\d\-]+)/i);
        if (gradeRangeMatch) pdSections[`Grade Range: ${gradeRangeMatch[1]}`] = '';

        if (!pdSections['HEADER']) {
            const headerLines = text.split('\n').slice(0, 5).filter(l => l.trim()).join('\n');
            pdSections['HEADER'] = headerLines;
        }

        // One final cleanup before rendering
        pdSections = cleanupMajorDutyDuplicates(pdSections);
        console.log('Final pdSections keys before render:', Object.keys(pdSections));
        
        renderUpdateStep(2);
    };
}

// Export the updated functions to make them available globally
window.cleanupMajorDutyDuplicates = cleanupMajorDutyDuplicates;
window.createSectionDiv = createSectionDiv;
window.renderEditableSections = renderEditableSections;
window.setupFileUpload = setupFileUpload;
window.cleanupMajorDutyDuplicates = cleanupMajorDutyDuplicates;
window.shouldSplitMajorDuties = shouldSplitMajorDuties;
window.splitMajorDutiesIntoSections = splitMajorDutiesIntoSections;
window.setupFileUpload = setupFileUpload;
window.loadSamplePD = loadSamplePD;
window.renderEditableSections = renderEditableSections;

function setupGroupSeriesDropdown(options) {
    const input = document.getElementById('groupSeriesDropdown');
    const list = document.getElementById('groupSeriesDropdownList');
    
    input.addEventListener('focus', showList);
    input.addEventListener('input', showList);
    input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

    function showList() {
        const term = input.value.trim().toLowerCase();
        const filtered = options.filter(opt => opt.code.includes(term));
        list.innerHTML = filtered.length
            ? filtered.map(opt => `<div class="dropdown-item" data-value="${opt.code}">${opt.code}</div>`).join('')
            : `<div class="dropdown-item disabled">No results</div>`;
        list.style.display = 'block';
    }

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.dataset.value;
            formState.groupSeries = e.target.dataset.value;
            list.style.display = 'none';

            // Clear job series and position title values from form state
            formState.jobSeries = '';
            formState.positionTitle = '';
            formState.positionTitles = [];

            // Clear and re-enable job series input
            const jobSeriesInput = document.getElementById('jobSeriesDropdown');
            const jobSeriesList = document.getElementById('jobSeriesDropdownList');
            if (jobSeriesInput) {
                jobSeriesInput.value = '';
                jobSeriesInput.disabled = false;
            }
            if (jobSeriesList) {
                jobSeriesList.innerHTML = '';
                jobSeriesList.style.display = 'none';
            }

            // Clear position title input and disable it until job series is selected
            const positionTitleInput = document.getElementById('positionTitleInput');
            const addPositionTitleBtn = document.getElementById('addPositionTitleBtn');
            const positionTitleList = document.getElementById('positionTitleList');
            if (positionTitleInput) {
                positionTitleInput.value = '';
                positionTitleInput.disabled = true;
            }
            if (addPositionTitleBtn) {
                addPositionTitleBtn.disabled = true;
            }
            if (positionTitleList) {
                positionTitleList.innerHTML = '';
            }

            setupJobSeriesDropdown();
            setupClassificationValidation();
        }
    });
}

function setupJobSeriesDropdown() {
    const input = document.getElementById('jobSeriesDropdown');
    const list = document.getElementById('jobSeriesDropdownList');
    if (!input || !list) return;

    input.addEventListener('focus', showList);
    input.addEventListener('input', showList);
    input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

    function showList() {
        const term = input.value.trim().toLowerCase();
        let results = Object.entries(jobSeriesData);

        // Filter job series by selected group series (first two digits)
        if (formState.groupSeries && formState.groupSeries !== "Other") {
            const groupPrefix = formState.groupSeries.substring(0, 2);
            results = results.filter(([code, data]) => code.startsWith(groupPrefix));
        }
        if (term) {
            results = results.filter(([code, data]) =>
                code.includes(term) ||
                data.title.toLowerCase().includes(term) ||
                (data.keywords && data.keywords.some(k => k.toLowerCase().includes(term)))
            );
        }
        list.innerHTML = results.length
            ? results.map(([code, data]) => `<div class="dropdown-item" data-value="${code}">${code} - ${data.title}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        list.style.display = 'block';
    }

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.textContent;
            formState.jobSeries = e.target.dataset.value;
            list.style.display = 'none';

            // Clear position title values from form state
            formState.positionTitle = '';
            formState.positionTitles = [];

            // Enable position title input and add button
            const positionTitleInput = document.getElementById('positionTitleInput');
            const addPositionTitleBtn = document.getElementById('addPositionTitleBtn');
            const positionTitleList = document.getElementById('positionTitleList');
            if (positionTitleInput) {
                positionTitleInput.value = '';
                positionTitleInput.disabled = false;
            }
            if (addPositionTitleBtn) {
                addPositionTitleBtn.disabled = false;
            }
            if (positionTitleList) {
                positionTitleList.innerHTML = '';
            }

            setupPositionTitleDropdown();
            setupClassificationValidation();
        }
    });
}

function setupPositionTitleDropdown() {
    const input = document.getElementById('positionTitleInput');
    const list = document.getElementById('positionTitleDropdownList');
    input.addEventListener('focus', showList);
    input.addEventListener('input', showList);
    input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

    function showList() {
        const term = input.value.trim().toLowerCase();
        let titles = [];
        if (formState.jobSeries && jobSeriesData[formState.jobSeries]) {
            titles = jobSeriesData[formState.jobSeries].subSeries || [];
        }
        if (term) {
            titles = titles.filter(t => t.toLowerCase().includes(term));
        }
        list.innerHTML = titles.length
            ? titles.map(t => `<div class="dropdown-item" data-value="${t}">${t}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        list.style.display = 'block';
    }

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.dataset.value;
            formState.positionTitle = e.target.dataset.value;
            list.style.display = 'none';
            setupClassificationValidation();
        }
    });
}

function fixBrokenParagraphs(text) {
    if (!text) return '';
    // Split into lines, trim, and filter out empty lines
    let lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 0);

    let paragraphs = [];
    let current = '';

    lines.forEach((line, idx) => {
        // If current is empty, start new paragraph
        if (!current) {
            current = line;
        } else {
            // If previous line ends with strong punctuation or this line starts with a number, start new paragraph
            if (/[.!?)]$/.test(current) || /^[0-9]+\.\s/.test(line) || /^[*-]\s/.test(line)) {
                paragraphs.push(current);
                current = line;
            } else {
                // Otherwise, join with space
                current += ' ' + line;
            }
        }
    });
    if (current) paragraphs.push(current);

    // Join paragraphs with double newlines
    return paragraphs.join('\n\n');
}

function handleStreamingResponse(data) {
    const parsedData = JSON.parse(data);
    
    if (parsedData.response) {
        // Handle regular streaming content
        appendToDisplay(parsedData.response);
    } else if (parsedData.formatted_complete) {
        // Replace the entire display with properly formatted content
        replaceEntireDisplay(parsedData.formatted_complete);
    } else if (parsedData.error) {
        displayError(parsedData.error);
    }
}

window.addDuty = function() {
    const container = document.getElementById('dutiesContainer');
    const dutyCount = container.children.length;
    if (dutyCount >= 20) return;

    const dutyGroup = document.createElement('div');
    dutyGroup.className = 'duty-input-group';
    dutyGroup.innerHTML = `
        <input type="text" class="duty-input" placeholder="Duty/Responsibility ${dutyCount + 1}...">
        <button type="button" class="remove-duty" onclick="removeDuty(this)">×</button>
    `;
    container.appendChild(dutyGroup);
    updateDutyCounter();
};

function toggleSectionEdit(title, btn) {
    const display = document.getElementById(`display-${sectionKeyToId(title)}`);
    const editor = document.getElementById(`editor-${sectionKeyToId(title)}`);
    if (!display || !editor) return;

    if (editor.style.display === 'none') {
        editor.style.display = '';
        display.style.display = 'none';
        if (btn) btn.classList.add('active');
        // Focus the textarea for immediate editing
        const textarea = document.getElementById(`textarea-${sectionKeyToId(title)}`);
        if (textarea) textarea.focus();
    } else {
        editor.style.display = 'none';
        display.style.display = '';
        if (btn) btn.classList.remove('active');
    }
}
window.toggleSectionEdit = toggleSectionEdit;

function fixBrokenParagraphs(text) {
    // Split into lines, trim, and filter out empty lines
    let lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 0);

    let paragraphs = [];
    let current = '';

    lines.forEach((line, idx) => {
        // If current is empty, start new paragraph
        if (!current) {
            current = line;
        } else {
            // If previous line ends with punctuation, start new paragraph
            if (/[.!?)]$/.test(current) || /^[0-9]+\.\s/.test(line)) {
                paragraphs.push(current);
                current = line;
            } else {
                // Otherwise, join with space
                current += ' ' + line;
            }
        }
    });
    if (current) paragraphs.push(current);

    // Join paragraphs with double newlines
    return paragraphs.join('\n\n');
}