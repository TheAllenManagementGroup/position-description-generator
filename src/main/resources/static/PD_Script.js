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
    loadAllSeriesAndUse();

    // Expose functions globally
    window.renderStep = renderStep;
    window.showUpdateApp = showUpdateApp;
    window.showMainApp = showMainApp;
});

// USA Jobs API Functions
async function fetchJobSeriesFromAPI(page = 1, allJobs = []) {
    try {
        console.log(`Fetching job series from backend proxy... Page ${page}`);
        
        const resultsPerPage = 2250;
        // Call your backend proxy endpoint instead of the USAJOBS API directly
        const response = await fetch(`/api/proxy/usajobs?ResultsPerPage=${resultsPerPage}&Page=${page}`, {
            method: 'GET'
        });

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
        console.error('Error fetching job series from backend proxy:', error);
        return {
            SearchResult: {
                SearchResultItems: allJobs,
                SearchResultCount: allJobs.length
            }
        };
    }
}

async function loadAllSeriesAndUse() {
    const seriesList = await fetchAllSeries();
    // Do something with the seriesList, e.g., display, filter, or store
    console.log('All unique series codes:', seriesList);

    // Example: populate a dropdown
    const dropdown = document.getElementById('allSeriesDropdown');
    if (dropdown) {
        dropdown.innerHTML = seriesList.map(code => `<option value="${code}">${code}</option>`).join('');
    }
}

async function fetchAllSeries() {
    let allSeries = new Set();
    let page = 1;
    let totalPages = 1;
    do {
        const response = await fetch(`/api/proxy/usajobs?ResultsPerPage=500&Page=${page}`);
        const data = await response.json();
        if (data.SearchResult && data.SearchResult.SearchResultItems) {
            data.SearchResult.SearchResultItems.forEach(item => {
                const job = item.MatchedObjectDescriptor;
                if (job.JobCategory) {
                    job.JobCategory.forEach(cat => {
                        if (cat.Code && /^\d{4}$/.test(cat.Code)) {
                            allSeries.add(cat.Code);
                        }
                    });
                }
            });
            totalPages = Math.ceil(data.SearchResult.SearchResultCount / 500);
        }
        page++;
    } while (page <= totalPages);
    return Array.from(allSeries);
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
        const groupTitle = jobSeriesData[seriesCode]?.groupName || '';
        if (!groupMap[groupCode]) {
            groupMap[groupCode] = {
                code: groupCode,
                name: groupTitle,
                seriesCodes: []
            };
        }
        groupMap[groupCode].seriesCodes.push(seriesCode);
    });
    // Sort numerically by code
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

        // --- FIX: Always provide at least one option for subSeries ---
        const positionsArr = Array.from(data.positions);
        const subSeriesArr = positionsArr.length > 0 ? positionsArr.slice(0, 8) : [cleanTitle];

        newJobSeriesData[seriesCode] = {
            title: cleanTitle,
            subSeries: subSeriesArr,
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

const positionTitleCache = {};

// Setup position title dropdown with fetched titles
async function setupPositionTitleDropdown() {
    const input = document.getElementById('positionTitleInput');
    const list = document.getElementById('positionTitleDropdownList');
    
    if (!input || !list) {
        console.log('Position title input or list not found');
        return;
    }
    
    // Don't set up if no job series is selected
    if (!formState.jobSeries) {
        console.log('No job series selected, skipping position title dropdown setup');
        return;
    }
    
    console.log('Setting up position title dropdown for job series:', formState.jobSeries);
    
    // Get titles from the already-loaded jobSeriesData
    let titles = [];
    if (jobSeriesData[formState.jobSeries] && Array.isArray(jobSeriesData[formState.jobSeries].subSeries)) {
        titles = jobSeriesData[formState.jobSeries].subSeries;
        console.log(`Found ${titles.length} cached titles for ${formState.jobSeries}`);
    }
    
    // If we have no titles, try fetching from API
    if (titles.length === 0) {
        console.log('No cached titles, fetching from API...');
        input.placeholder = 'Loading titles from USAJobs...';
        input.disabled = true;
        
        try {
            titles = await fetchPositionTitlesFromAPI(formState.jobSeries);
            console.log(`Fetched ${titles.length} titles from API`);
            input.placeholder = 'Search or select position title...';
            input.disabled = false;
        } catch (error) {
            console.error('Failed to fetch titles:', error);
            input.placeholder = 'Type position title...';
            input.disabled = false;
            titles = [];
        }
    }
    
    // If still no titles, allow free text input
    if (titles.length === 0) {
        console.log('No titles available, enabling free text input');
        input.placeholder = 'Type position title...';
        input.disabled = false;
        return;
    }
    
    // Setup the dropdown with the titles we have
    function showList() {
        const term = input.value.trim().toLowerCase();
        const filtered = titles.filter(t => t.toLowerCase().includes(term));
        list.innerHTML = filtered.length
            ? filtered.map(t => `<div class="dropdown-item" data-value="${t}">${t}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        list.style.display = 'block';
    }

    // Remove old event listeners by cloning and replacing
    const newInput = input.cloneNode(true);
    const newList = list.cloneNode(true);
    input.parentNode.replaceChild(newInput, input);
    list.parentNode.replaceChild(newList, list);
    
    // Get fresh references
    const freshInput = document.getElementById('positionTitleInput');
    const freshList = document.getElementById('positionTitleDropdownList');

    freshInput.addEventListener('focus', showList);
    freshInput.addEventListener('input', function() {
        showList();
        formState.positionTitle = this.value;
    });
    freshInput.addEventListener('blur', () => setTimeout(() => freshList.style.display = 'none', 150));

    freshList.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            freshInput.value = e.target.dataset.value;
            formState.positionTitle = e.target.dataset.value;
            freshList.style.display = 'none';
            if (typeof setupClassificationValidation === 'function') {
                setupClassificationValidation();
            }
        }
    });
    
    console.log('Position title dropdown setup complete');
}

// Function to fetch position titles from USA Jobs API for a specific series
async function fetchPositionTitlesFromAPI(jobSeries) {
    // Check cache first
    if (positionTitleCache[jobSeries]) {
        console.log(`Using cached position titles for ${jobSeries}`);
        return positionTitleCache[jobSeries];
    }

    console.log(`Fetching position titles for series ${jobSeries} from API...`);
    
    try {
        // Use the proxy endpoint with series filter
        const response = await fetch(`/api/proxy/usajobs?ResultsPerPage=500&JobCategoryCode=${jobSeries}`, {
            method: 'GET'
        });

        if (!response.ok) {
            throw new Error(`API request failed: ${response.status} ${response.statusText}`);
        }

        const data = await response.json();
        
        // Extract unique position titles from the response
        const titles = new Set();
        
        if (data.SearchResult && data.SearchResult.SearchResultItems) {
            data.SearchResult.SearchResultItems.forEach(item => {
                const job = item.MatchedObjectDescriptor;
                if (job.PositionTitle) {
                    titles.add(job.PositionTitle);
                }
            });
        }

        // Convert Set to Array and sort
        const titlesArray = Array.from(titles).sort();
        
        // Fallback if no titles found
        if (titlesArray.length === 0) {
            console.log(`No titles found via API for ${jobSeries}, using fallback`);
            if (jobSeriesData[jobSeries] && Array.isArray(jobSeriesData[jobSeries].subSeries)) {
                return jobSeriesData[jobSeries].subSeries;
            }
            return [jobSeriesData[jobSeries]?.title || 'Position Title'];
        }

        // Cache the results
        positionTitleCache[jobSeries] = titlesArray;
        
        console.log(`Found ${titlesArray.length} position titles for ${jobSeries}`);
        return titlesArray;
        
    } catch (error) {
        console.error(`Error fetching position titles for ${jobSeries}:`, error);
        // Return cached data as fallback
        if (jobSeriesData[jobSeries] && Array.isArray(jobSeriesData[jobSeries].subSeries)) {
            return jobSeriesData[jobSeries].subSeries;
        }
        return [jobSeriesData[jobSeries]?.title || 'Position Title'];
    }
}

async function renderPositionTitleField() {
    console.log('=== renderPositionTitleField called ===');
    console.log('formState.jobSeries:', formState.jobSeries);

    const group = document.getElementById('positionTitleGroup');
    if (!group) {
        console.error('positionTitleGroup element not found');
        return;
    }

    // If no job series selected yet, show disabled field
    if (!formState.jobSeries) {
        console.log('No job series selected yet');
        group.innerHTML = `
            <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <input class="form-input" id="positionTitleInput" autocomplete="off"
                    placeholder="Select job series first..."
                    value="" disabled>
                <div class="dropdown-list" id="positionTitleDropdownList" style="display:none;"></div>
            </div>
        `;
        return;
    }

    console.log('Job series selected:', formState.jobSeries);

    // Special case: 0301 gets a free text input
    if (formState.jobSeries === '0301') {
        console.log('Rendering free text input for 0301');
        group.innerHTML = `
            <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
            <input class="form-input" id="positionTitleInput" autocomplete="off"
                placeholder="Type position title..."
                value="${formState.positionTitle || ''}">
        `;
        const input = document.getElementById('positionTitleInput');
        if (input) {
            input.disabled = false;
            input.addEventListener('input', function() {
                formState.positionTitle = this.value;
                autofillSupervisoryLevelFromTitle(this.value);
                if (typeof window.checkComplete === 'function') {
                    window.checkComplete();
                }
            });
        }
        return;
    }

    // For all other series: show loading state, then fetch from API
    console.log('Fetching titles for job series:', formState.jobSeries);
    group.innerHTML = `
        <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
        <div class="dropdown-wrapper">
            <input class="form-input" id="positionTitleInput" autocomplete="off"
                placeholder="Loading position titles from USA Jobs..."
                value="${formState.positionTitle || ''}" disabled>
            <div class="dropdown-list" id="positionTitleDropdownList" style="display:none;"></div>
        </div>
        <div style="color:#64748b; font-size:0.9em; margin-top:0.5rem;">
            <span class="spinner" style="width:12px; height:12px; border-width:2px;"></span> Fetching titles from USAJobs.gov...
        </div>
    `;

    try {
        // Fetch position titles from API
        const titles = await fetchPositionTitlesFromAPI(formState.jobSeries);

        console.log('Fetched titles:', titles);

        // Validate that we have an array
        if (!Array.isArray(titles) || titles.length === 0) {
            console.warn('No titles returned from API, using fallback');
            group.innerHTML = `
                <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
                <input class="form-input" id="positionTitleInput" autocomplete="off"
                    placeholder="Type position title..."
                    value="${formState.positionTitle || ''}">
                <div style="color:#ef4444; font-size:0.9em; margin-top:0.5rem;">
                    <i class="fas fa-exclamation-triangle"></i> No titles found. Please enter manually.
                </div>
            `;

            const input = document.getElementById('positionTitleInput');
            if (input) {
                input.disabled = false;
                input.addEventListener('input', function() {
                    formState.positionTitle = this.value;
                    autofillSupervisoryLevelFromTitle(this.value);
                    if (typeof window.checkComplete === 'function') {
                        window.checkComplete();
                    }
                });
            }
            return;
        }

        // Update the field with the fetched titles
        group.innerHTML = `
            <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <input class="form-input" id="positionTitleInput" autocomplete="off"
                    placeholder="Search or select position title..."
                    value="${formState.positionTitle || ''}">
                <div class="dropdown-list" id="positionTitleDropdownList" style="display:none;"></div>
            </div>
            <div style="color:#10b981; font-size:0.9em; margin-top:0.5rem;">
                <i class="fas fa-check-circle"></i> Loaded ${titles.length} position titles from USAJobs.gov
            </div>
        `;

        // Setup dropdown with proper event handlers
        const input = document.getElementById('positionTitleInput');
        const list = document.getElementById('positionTitleDropdownList');

        if (input && list) {
            input.disabled = false;

            function showList() {
                const term = input.value.trim().toLowerCase();
                const filtered = titles.filter(t => t.toLowerCase().includes(term));
                list.innerHTML = filtered.length
                    ? filtered.map(t => `<div class="dropdown-item" data-value="${t}">${t}</div>`).join('')
                    : `<div class="dropdown-item disabled">No results found</div>`;
                list.style.display = 'block';
            }

            input.addEventListener('focus', showList);
            input.addEventListener('input', function() {
                showList();
                formState.positionTitle = this.value;
                autofillSupervisoryLevelFromTitle(this.value);
                if (typeof window.checkComplete === 'function') {
                    window.checkComplete();
                }
            });
            input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

            list.addEventListener('mousedown', function(e) {
                if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
                    input.value = e.target.dataset.value;
                    formState.positionTitle = e.target.dataset.value;
                    autofillSupervisoryLevelFromTitle(e.target.dataset.value);
                    list.style.display = 'none';
                    if (typeof window.checkComplete === 'function') {
                        window.checkComplete();
                    }
                }
            });
        }

        // Call checkComplete after setup to update button state
        if (typeof window.checkComplete === 'function') {
            window.checkComplete();
        }

    } catch (error) {
        console.error('Error fetching position titles:', error);

        // Fallback to manual entry on error
        group.innerHTML = `
            <label class="form-label" for="positionTitleInput">Position Title <span style="color:#ef4444;">*</span></label>
            <input class="form-input" id="positionTitleInput" autocomplete="off"
                placeholder="Type position title..."
                value="${formState.positionTitle || ''}">
            <div style="color:#ef4444; font-size:0.9em; margin-top:0.5rem;">
                <i class="fas fa-exclamation-triangle"></i> Unable to fetch titles. Please enter manually.
            </div>
        `;

        const input = document.getElementById('positionTitleInput');
        if (input) {
            input.disabled = false;
            input.addEventListener('input', function() {
                formState.positionTitle = this.value;
                autofillSupervisoryLevelFromTitle(this.value);
                if (typeof window.checkComplete === 'function') {
                    window.checkComplete();
                }
            });
        }
    }
}

function autofillSupervisoryLevelFromTitle(positionTitle) {
    if (!positionTitle || typeof positionTitle !== 'string') {
        formState.supervisoryLevel = '';
        return;
    }
    const title = positionTitle.toLowerCase();
    if (/\bsupervisor\b|\bsupervisory\b|\bsuperintendent\b|\bchief\b|\bmanager\b|\bdirector\b/.test(title)) {
        formState.supervisoryLevel = 'Supervisor';
    } else if (/\blead\b/.test(title)) {
        formState.supervisoryLevel = 'Team Lead';
    } else {
        formState.supervisoryLevel = 'Non-Supervisory';
    }
}
window.autofillSupervisoryLevelFromTitle = autofillSupervisoryLevelFromTitle;


function setupEvalStatementExportButtons(formattedText) {
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
}
window.setupEvalStatementExportButtons = setupEvalStatementExportButtons;

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

    // Track all visited steps (not just the highest)
    if (!window.visitedSteps) window.visitedSteps = new Set();
    window.visitedSteps.add(currentStep);

    container.innerHTML = '';
    steps.forEach((step, idx) => {
        const stepNum = idx + 1;
        const isVisited = window.visitedSteps.has(stepNum);
        const isCurrent = stepNum === currentStep;

        // Colors
        const darkTeal = '#0f766e';
        const green = '#10b981';
        const lightBlue = '#e0f2fe';

        // Icon logic
        let iconHtml = '';
        if (isVisited && !isCurrent) {
            iconHtml = `<div class="step-icon completed" style="background:${darkTeal};color:#fff;">
                <i class="fas fa-check"></i>
            </div>`;
        } else if (isCurrent) {
            iconHtml = `<div class="step-icon current" style="background:${green};display:flex;align-items:center;justify-content:center;">
                <div style="width:14px;height:14px;background:#fff;border-radius:50%;"></div>
            </div>`;
        } else {
            iconHtml = `<div class="step-icon upcoming" style="background:#fff;color:${darkTeal};border:2px solid ${darkTeal};font-weight:700;opacity:0.4;">
                ${stepNum}
            </div>`;
        }

        let stepClass = 'step';
        if (isVisited && !isCurrent) stepClass += ' completed';
        else if (isCurrent) stepClass += ' current';
        else stepClass += ' upcoming';

        // Visited and current steps get light blue background
        let highlightStyle = '';
        if (isVisited || isCurrent) {
            highlightStyle = `background:${lightBlue} !important; opacity:1;`;
        } else {
            highlightStyle = `background:#fff !important; opacity:0.4;`;
        }

        // Allow navigation to any visited step (forward or backward)
        let clickable = '';
        if (isVisited && !isCurrent) {
            clickable = `style="cursor:pointer;" onclick="renderStep(${stepNum})"`;
        }

        container.innerHTML += `
            <div class="${stepClass}" ${clickable} style="display:flex;align-items:center;gap:12px;padding:10px 16px;border-radius:8px;margin-bottom:8px;min-height:40px;${highlightStyle}">
                ${iconHtml}
                <div class="step-content" style="flex:1;">
                    <div class="step-name" style="font-size:15px;font-weight:600;color:#111827;">${step.name}</div>
                    <div class="step-description" style="font-size:12px;color:#111827;">${step.description}</div>
                </div>
            </div>
        `;
    });
}

function renderUpdateSidebar(currentStep) {
    const container = document.getElementById('updateStepsContainer');
    if (!container) return;

    // Track all visited update steps
    if (!window.visitedUpdateSteps) window.visitedUpdateSteps = new Set();
    window.visitedUpdateSteps.add(currentStep);

    container.innerHTML = '';
    updateSteps.forEach((step, idx) => {
        const stepNum = idx + 1;
        const isVisited = window.visitedUpdateSteps.has(stepNum);
        const isCurrent = stepNum === currentStep;

        // Colors
        const darkTeal = '#0f766e';
        const green = '#10b981';
        const lightBlue = '#e0f2fe';

        let iconHtml = '';
        if (isVisited && !isCurrent) {
            iconHtml = `<div class="step-icon completed" style="background:${darkTeal};color:#fff;">
                <i class="fas fa-check"></i>
            </div>`;
        } else if (isCurrent) {
            iconHtml = `<div class="step-icon current" style="background:${green};display:flex;align-items:center;justify-content:center;">
                <div style="width:14px;height:14px;background:#fff;border-radius:50%;"></div>
            </div>`;
        } else {
            iconHtml = `<div class="step-icon upcoming" style="background:#fff;color:${darkTeal};border:2px solid ${darkTeal};font-weight:700;opacity:0.4;">
                ${stepNum}
            </div>`;
        }

        let stepClass = 'step-item';
        if (isVisited && !isCurrent) stepClass += ' completed';
        else if (isCurrent) stepClass += ' current';
        else stepClass += ' upcoming';

        // Visited and current steps get light blue background
        let highlightStyle = '';
        if (isVisited || isCurrent) {
            highlightStyle = `background:${lightBlue} !important; opacity:1;`;
        } else {
            highlightStyle = `background:#fff !important; opacity:0.4;`;
        }

        // Allow navigation to any visited step (forward or backward)
        let clickable = '';
        if (isVisited && !isCurrent) {
            clickable = `style="cursor:pointer;" onclick="renderUpdateStep(${stepNum})"`;
        }

        container.innerHTML += `
            <div class="${stepClass}" ${clickable} style="display:flex;align-items:center;gap:12px;padding:10px 16px;border-radius:8px;margin-bottom:8px;min-height:40px;${highlightStyle}">
                ${iconHtml}
                <div class="step-content" style="flex:1;">
                    <div class="step-name" style="font-size:15px;font-weight:600;color:#111827;">${step.name}</div>
                    <div class="step-description" style="font-size:12px;color:#111827;">${step.description}</div>
                </div>
            </div>
        `;
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
            renderDutiesStepWithPrefill(content);
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

function setupSupervisoryLevelDropdown() {
    const levels = [
        "Non-Supervisory",
        "Team Lead",
        "Supervisor",
    ];
    const input = document.getElementById('supervisoryLevelDropdown');
    const list = document.getElementById('supervisoryLevelDropdownList');
    if (!input || !list) return;

    function showList() {
        const term = input.value.trim().toLowerCase();
        const filtered = levels.filter(level => level.toLowerCase().includes(term));
        list.innerHTML = filtered.length
            ? filtered.map(level => `<div class="dropdown-item" data-value="${level}">${level}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        list.style.display = 'block';
    }

    input.addEventListener('focus', showList);
    input.addEventListener('input', showList);
    input.addEventListener('blur', () => setTimeout(() => list.style.display = 'none', 150));

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.textContent;
            formState.supervisoryLevel = e.target.dataset.value;
            list.style.display = 'none';
            updateSupervisoryContinueButton();
        }
    });

    function updateSupervisoryContinueButton() {
    }
}

renderSupervisoryLevelStep(document.getElementById('stepContent'));
setupSupervisoryLevelDropdown();

// Usage example in your step rendering:
function renderSupervisoryLevelStep(content) {
    content.innerHTML = `
        <div class="form-group" style="max-width: 400px; padding-left: 1.5rem;">
            <label class="form-label" for="supervisoryLevelDropdown">Supervisory Level<span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <input class="form-input" id="supervisoryLevelDropdown" autocomplete="off" placeholder="Search or select supervisory level..." value="${formState.supervisoryLevel || ''}">
                <div class="dropdown-list" id="supervisoryLevelDropdownList" style="display:none;"></div>
            </div>
        </div>
    `;
    setupSupervisoryLevelDropdown();
}

function showTitleSeriesAboveGradeAnalysis(duties, gsGrade, jobSeries, jobTitle, supervisoryLevel) {
    const container = document.getElementById('titleSeriesSection');
    if (!container) return;
    container.innerHTML = '<span class="spinner"></span> Generating Title and Series Determination...';
    fetch('/api/generate-title-series', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ duties, gsGrade, jobSeries, jobTitle, supervisoryLevel })
    })
    .then(response => response.json())
    .then(data => {
        container.innerHTML = `<div class="title-series-section">${markdownToHTML(data.titleSeriesSection)}</div>`;
    })
    .catch(() => {
        container.innerHTML = '<span style="color:red;">Error generating Title and Series Determination.</span>';
    });
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
        setTimeout(() => renderJobClassificationStep(content), 500);
        return;
    }

    const groupSeriesOptions = getGroupSeriesOptionsFromAPI();

    content.innerHTML = `
        <div class="card">
            <div class="card-header">
                <div class="card-title"><i class="fas fa-users"></i> Job Classification
                    <span class="step-info">
                        <i class="fas fa-info-circle" style="color:#2563eb; margin-left:6px; cursor:help;"></i>
                        <div class="step-tooltip">
                            Select the group series and job series for the position. Position titles are fetched live from USAJobs.gov.
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
                <div class="form-group" id="positionTitleGroup">
                    <!-- Position Title input/dropdown will be rendered here -->
                </div>
                <div class="form-group">
                    <label class="form-label" for="organizationalTitleInput">Organizational Title <span style="color:#64748b; font-weight:400;">(optional)</span></label>
                    <input class="form-input" id="organizationalTitleInput" autocomplete="off" placeholder="Type your organizational title..." value="${formState.organizationalTitle || ''}">
                    <div class="form-hint" style="color:#64748b; font-size:0.95em;">This is the working title used in your organization (e.g., 'Program Manager').</div>
                </div>
                <div class="step-actions" style="display: flex; justify-content: space-between; align-items: center; margin-top: 2rem;">
                    <button class="btn btn-outline" id="previousStepBtn" style="margin-right:auto;">
                        <i class="fas fa-arrow-left"></i> Previous
                    </button>
                    <button class="btn btn-primary" id="continueClassificationBtn" style="margin-left:auto;">
                        ${formState.usedAIRecommendation ? 'Continue: To Review & Generate' : 'Next: Duties & Responsibilities'}
                    </button>
                </div>
            </div>
        </div>
    `;

    document.getElementById('previousStepBtn').onclick = function() {
        renderStep(2);
    };

    setupGroupSeriesDropdown(groupSeriesOptions);
    setupJobSeriesDropdown();

    // Initial render of Position Title field (will show "Select job series first..." if empty)
    renderPositionTitleField();

    // Organizational Title logic
    const orgTitleInput = document.getElementById('organizationalTitleInput');
    if (orgTitleInput) {
        orgTitleInput.addEventListener('input', function() {
            formState.organizationalTitle = this.value;
            checkComplete();
        });
    }

    // Validation logic - MUST be defined before calling checkComplete()
    const continueBtn = document.getElementById('continueClassificationBtn');
    const groupSeriesInput = document.getElementById('groupSeriesDropdown');
    const jobSeriesInput = document.getElementById('jobSeriesDropdown');

    // Make checkComplete available for other functions
    window.checkComplete = function() {
        const groupSeriesFilled = groupSeriesInput.value.trim() !== '';
        const jobSeriesFilled = jobSeriesInput.value.trim() !== '';
        const positionTitleFilled = (formState.positionTitle || '').trim() !== '';
        if (groupSeriesFilled && jobSeriesFilled && positionTitleFilled) {
            continueBtn.disabled = false;
            continueBtn.style.opacity = '1';
            continueBtn.style.cursor = 'pointer';
        } else {
            continueBtn.disabled = true;
            continueBtn.style.opacity = '0.6';
            continueBtn.style.cursor = 'not-allowed';
        }
    };

    groupSeriesInput.addEventListener('input', window.checkComplete);
    jobSeriesInput.addEventListener('input', window.checkComplete);

    continueBtn.onclick = function() {
        if (!continueBtn.disabled) {
            if (formState.usedAIRecommendation) {
                renderStep(5);
            } else {
                renderStep(4);
            }
        }
    };

    document.getElementById('unknownSeriesBtn').onclick = () => renderStep('unknownSeries');

    // Listen for job series changes and update Position Title field immediately
    document.getElementById('jobSeriesDropdown').addEventListener('input', async function() {
        const newSeries = this.value;
        if (newSeries !== formState.jobSeries) {
            formState.jobSeries = newSeries;
            formState.positionTitle = '';
            await renderPositionTitleField();
            window.checkComplete();
        }
    });
    
    // Run initial validation check
    window.checkComplete();
}

// Export functions to window for global access
window.setupPositionTitleDropdown = setupPositionTitleDropdown;
window.fetchPositionTitlesFromAPI = fetchPositionTitlesFromAPI;
window.renderPositionTitleField = renderPositionTitleField;
window.renderJobClassificationStep = renderJobClassificationStep;
window.positionTitleCache = positionTitleCache;

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
        <div class="form-group" style="max-width: 400px; padding-left: 1.5rem;">
            <label class="form-label" for="supervisoryLevelSelect">Supervisory Level<span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <input class="form-input" id="supervisoryLevelDropdown" autocomplete="off" placeholder="Search or select supervisory level..." value="${formState.supervisoryLevel || ''}">
                <div class="dropdown-list" id="supervisoryLevelDropdownList" style="display:none;"></div>
            </div>
        </div>
        <div class="card-content">
            <div style="margin-bottom:1rem;">
                <label for="aiDutiesInput" style="font-weight:600;">AI Rewrite Duties/Responsibilities</label>
                <textarea id="aiDutiesInput" class="form-input" rows="4" placeholder="Paste or write your duties/responsibilities here..."></textarea>
                <div style="color:#6b7280; font-size:0.9em; margin-top:0.5rem;">
                    Tip: You may include approximate percentages to indicate time or effort for each duty (for example <em>"Manage contracts — 30%"</em>). The system recognizes and interprets percentages when generating grade and series recommendations.
                </div>
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
            <div style="display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem;">
                <div style="flex:1"></div>
                <button class="btn btn-primary" id="getAIRecommendationBtn" style="margin-bottom:0;">
                    Get AI Recommendation
                </button>
            </div>
            <div id="dutyCounter" class="badge"></div>
            <div id="aiSeriesResult" style="margin-top:1.5rem; margin-bottom:1.5rem;"></div>
            <div id="gradeAnalysisResult"></div>
            <div class="step-actions" style="display: flex; justify-content: space-between; align-items: center; margin-top: 2rem;">
                <button class="btn btn-outline" id="previousUnknownBtn" style="margin-right:auto;">
                    <i class="fas fa-arrow-left"></i> Previous
                </button>
                <button class="btn btn-primary" id="continueUnknownSeriesBtn" style="display:none; margin-left:auto;">
                    Continue
                </button>
            </div>
        </div>
    </div>
    `;

    // Previous button logic (goes back to Job Classification step)
    document.getElementById('previousUnknownBtn').onclick = function() {
        renderStep(3);
    };

    // Supervisory dropdown logic
    const supervisorySelect = document.getElementById('supervisoryLevelSelect');
    if (supervisorySelect) {
        supervisorySelect.value = formState.supervisoryLevel || '';
        supervisorySelect.onchange = function() {
            formState.supervisoryLevel = this.value;
        };
    }

    // Add Clear Duties button logic
    const clearBtn = document.getElementById('clearDutiesBtn');
    if (clearBtn) {
        clearBtn.onclick = function() {
            formState.unknownDuties = [];
            renderDutyInputs(Array(6).fill(''));
            updateDutyCounter();
        };
    }

    setupUnknownSeriesLogic();
    setupSupervisoryLevelDropdown();

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

    // Always render numbered duty inputs
    if (formState.duties.length > 0) {
        renderDutyInputs(formState.duties);
    } else {
        // If no duties, add 6 empty inputs
        renderDutyInputs(Array(6).fill(''));
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

async function renderDutiesStepWithPrefill(content) {
    await maybePrefillDutyFor0343();
    renderDutiesStep(content);
}

function renderDutiesStep(content) {
    if (!Array.isArray(formState.duties) || formState.duties.length < 6) {
        formState.duties = Array(6).fill('').map((_, i) => formState.duties[i] || '');
    }

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
        <div class="form-group" style="max-width: 400px; padding-left: 1.5rem;">
            <label class="form-label" for="supervisoryLevelSelect">Supervisory Level<span style="color:#ef4444;">*</span></label>
            <div class="dropdown-wrapper">
                <input class="form-input" id="supervisoryLevelDropdown" autocomplete="off" placeholder="Search or select supervisory level..." value="${formState.supervisoryLevel || ''}">
                <div class="dropdown-list" id="supervisoryLevelDropdownList" style="display:none;"></div>
            </div>
        </div>
        <div class="card-content">
            <div style="margin-bottom:1rem;">
                <label for="aiDutiesInput" style="font-weight:600;">AI Rewrite Duties/Responsibilities</label>
                <textarea id="aiDutiesInput" class="form-input" rows="4" placeholder="Paste or write your duties/responsibilities here..."></textarea>
                <div style="color:#6b7280; font-size:0.9em; margin-top:0.5rem;">
                    Tip: You may include approximate percentages to indicate time or effort for each duty (for example <em>"Manage contracts — 30%"</em>). The system recognizes and interprets percentages when generating grade and series recommendations.
                </div>
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
            <div style="display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem;">
                <div style="flex:1"></div>
                <button class="btn btn-primary" id="gradeAnalysisBtn" style="margin-bottom:0;">
                    Grade Analysis
                </button>
            </div>
            <div id="dutyCounter" class="badge"></div>
            <div id="titleSeriesSection"></div>
            <div id="gradeAnalysisResult" style="margin-top:2rem;"></div>
            <div class="step-actions" style="display: flex; justify-content: space-between; align-items: center; margin-top: 2rem;">
                <button class="btn btn-outline" id="previousStepBtn" style="margin-right:auto;">
                    <i class="fas fa-arrow-left"></i> Previous
                </button>
                <button class="btn btn-primary" id="continueDutiesBtn" style="display:none;">
                    Continue to Review & Generate
                </button>
            </div>
        </div>
    </div>
    `;

    // render inputs (primary + secondary layout)
    if (typeof window.renderDutyInputs === 'function') {
        window.renderDutyInputs(formState.duties);
    }

    // previous button (guarded)
    const prevBtn = document.getElementById('previousStepBtn');
    if (prevBtn) prevBtn.addEventListener('click', () => renderStep(3));

    // clear button (may not exist in every context) - guard before binding
    const clearBtn = document.getElementById('clearDutiesBtn');
    if (clearBtn) {
        clearBtn.addEventListener('click', () => {
            formState.duties = [];
            if (typeof window.renderDutyInputs === 'function') {
                renderDutyInputs(Array(6).fill(''));
            }
            updateDutyCounter();
        });
    }

    // supervisory dropdown wiring - use the actual input id if present
    const supervisoryDropdown = document.getElementById('supervisoryLevelDropdown');
    if (supervisoryDropdown) {
        supervisoryDropdown.value = formState.supervisoryLevel || '';
        supervisoryDropdown.addEventListener('input', function() {
            formState.supervisoryLevel = this.value;
        });
    }

    // AI rewrite handler - attach safely and prevent earlier JS errors from blocking it
    const aiBtn = document.getElementById('aiRewriteBtn');
    if (aiBtn) {
        // remove any previous listeners to avoid duplicates
        aiBtn.replaceWith(aiBtn.cloneNode(true));
    }
    const freshAiBtn = document.getElementById('aiRewriteBtn');
    if (freshAiBtn) {
        freshAiBtn.addEventListener('click', async function () {
            const textarea = document.getElementById('aiDutiesInput');
            const resultDiv = document.getElementById('aiRewriteResult');
            if (!textarea || !resultDiv) return;

            const dutiesText = textarea.value.trim();
            if (!dutiesText) {
                resultDiv.innerHTML = '<span style="color:red;">Please enter duties to rewrite.</span>';
                return;
            }

            resultDiv.innerHTML = '<span class="spinner"></span> Rewriting duties with AI...';

            try {
                const response = await fetch('/api/rewrite-duties-sync', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ duties: dutiesText })
                });

                if (!response.ok) throw new Error(`Server returned ${response.status}`);

                const data = await response.json();
                let rewritten = data.rewritten || '';

                // Normalize output into array of non-empty lines
                const rewrittenLines = rewritten
                    .split('\n')
                    .map(line => line.replace(/^\s*[\d]+[.)-]?\s*/g, '').replace(/^\s*[-•*]+\s*/g, '').trim())
                    .filter(line => line.length > 0);

                resultDiv.innerHTML = `
                    <div style="margin-bottom:1em; max-width:100%; word-break:break-word; white-space:pre-line; background:#f3f4f6; border-radius:0.5em; padding:1em;">
                        <strong>Rewritten Duties:</strong>
                        <div style="margin-top:0.5em;">
                            ${rewrittenLines.map(duty => `<div style="margin-bottom:0.5em; word-break:break-word; white-space:pre-line;">${duty}</div>`).join('')}
                        </div>
                    </div>
                    <button class="btn btn-primary" id="useRewritesBtn">
                        <i class="fas fa-arrow-right"></i> Use Rewrites
                    </button>
                `;

                const useBtn = document.getElementById('useRewritesBtn');
                if (useBtn) {
                    useBtn.addEventListener('click', () => {
                        // preserve original shape: primary + secondaries expected by renderDutyInputs
                        formState.unknownDuties = rewrittenLines;
                        if (typeof window.renderDutyInputs === 'function') {
                            renderDutyInputs(formState.unknownDuties);
                        }
                        updateDutyCounter();

                        // enable grade/AI buttons if enough duties
                        const gradeBtn = document.getElementById('gradeAnalysisBtn');
                        if (gradeBtn) {
                            const count = (formState.unknownDuties || []).filter(d => d && d.trim()).length;
                            if (count >= 6) {
                                gradeBtn.style.display = '';
                                gradeBtn.disabled = false;
                                gradeBtn.style.opacity = '1';
                                gradeBtn.style.cursor = 'pointer';
                            } else {
                                gradeBtn.style.display = 'none';
                            }
                        }

                        const getAIRecBtn = document.getElementById('getAIRecommendationBtn');
                        if (getAIRecBtn) {
                            const count = (formState.unknownDuties || []).filter(d => d && d.trim()).length;
                            getAIRecBtn.disabled = !(count >= 6);
                            getAIRecBtn.style.opacity = count >= 6 ? '1' : '0.6';
                            getAIRecBtn.style.cursor = count >= 6 ? 'pointer' : 'not-allowed';
                        }
                    });
                }

            } catch (err) {
                console.error('AI rewrite failed', err);
                resultDiv.innerHTML = '<span style="color:red;">Error rewriting duties.</span>';
            }
        });
    }

    // initialize dropdowns and duties logic
    setupSupervisoryLevelDropdown();
    setupDutiesLogic();
}

function renderReviewGenerateStep(content) {
    // Get job series display value (number and title)
    let jobSeriesDisplay = '<span style="color:#9ca3af;">Not set</span>';
    if (formState.jobSeries) {
        let code = formState.jobSeries;
        let title = '';
        if (jobSeriesData[code]) {
            title = jobSeriesData[code].title;
        } else if (formState.jobSeries.includes(' - ')) {
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
                        ${formState.organizationalTitle && formState.organizationalTitle.trim() ? `
                        <li><strong>Organizational Title:</strong> ${formState.organizationalTitle}</li>
                        ` : ''}
                        <li><strong>GS Grade:</strong> ${formState.gsGrade || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Supervisory Level:</strong> ${formState.supervisoryLevel || '<span style="color:#9ca3af;">Not set</span>'}</li>
                        <li><strong>Duties & Responsibilities:</strong></li>
                    </ul>
                    <ul style="padding-left:1.5em;">
                        ${(formState.duties || []).filter(d => d.trim()).map(duty => `<li>${duty}</li>`).join('') || '<li style="color:#9ca3af;">No duties entered</li>'}
                    </ul>
                </div>
                <div class="step-actions" style="display: flex; justify-content: space-between; align-items: center; margin-top: 2rem;">
                    <button class="btn btn-outline" id="previousReviewBtn" style="margin-right:auto;">
                        <i class="fas fa-arrow-left"></i> Previous
                    </button>
                    <button class="btn btn-primary" id="generateBtn" style="margin-left:auto;">
                        Generate Position Description
                    </button>
                </div>
                <div id="aiResult" style="margin-top:24px;"></div>
            </div>
        </div>
    `;

    document.getElementById('previousReviewBtn').onclick = function() {
        renderStep(4); // Go back to Duties & Responsibilities step
    };

    setupGenerateButton();
}

function fixPDFormatting(pdText) {
    if (!pdText) return '';
    let text = pdText.replace(/\r\n/g, '\n').trim();

    // --- HEADER variable splitting: force each field onto its own line, even if run together ---
    text = text.replace(/(Job Series:[^\n]*)(Position Title:)/gi, '$1\n$2');
    text = text.replace(/(Position Title:[^\n]*)(Agency:)/gi, '$1\n$2');
    text = text.replace(/(Agency:[^\n]*)(Organization:)/gi, '$1\n$2');
    text = text.replace(/(Organization:[^\n]*)(Lowest Organization:)/gi, '$1\n$2');
    text = text.replace(/(Lowest Organization:[^\n]*)(Supervisory Level:)/gi, '$1\n$2');
    text = text.replace(/(Supervisory Level:[^\n]*)(Grade:)/gi, '$1\n$2');
    // Remove accidental multiple newlines
    text = text.replace(/\n{3,}/g, '\n\n');

    // Remove asterisks between factor sections
    text = text.replaceAll("\\*\\*\\s*\\n+\\s*\\*\\*", "\n\n");

    // --- Section headers bold and spaced ---
    const sectionHeaders = [
        'HEADER',
        'INTRODUCTION',
        'MAJOR DUTIES',
        'FACTOR EVALUATION - COMPLETE ANALYSIS',
        'FACTOR EVALUATION',
        'EVALUATION SUMMARY',
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ];
    sectionHeaders.forEach(header => {
        const regex = new RegExp("\\*{0,2}\\s*" + header + "\\s*:?\\s*\\*{0,2}", "gi");
        text = text.replace(regex, `\n\n**${header}**\n\n`);
    });

    // --- AGGRESSIVE Factor header fix: split level and points if run together (e.g., 1-71250 -> 1-7, 1250) ---
    // Handles "Level 1-71250" or "Level 1-7125, 0 Points"
    text = text.replace(/Level\s*([0-9]+)-([0-9])([0-9]{3,4})(,?\s*[0-9]+\s*Points)/g, 'Level $1-$2, $3$4');

    // --- Ensure all factor headers are bolded, on their own line, and formatted correctly ---
    // Handles both bolded and non-bolded, and ensures two blank lines before each
    text = text.replace(
        /(?<!\n)\s*\*?\*?\s*Factor\s*([0-9])\s*[–-]?\s*([^\n*]+?)\s*Level\s*([0-9]+)-([0-9]+),\s*([0-9]{2,5})\s*Points\s*\*?\*?/g,
        '\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n'
    );

    // --- Remove extra spaces and blank lines ---
    text = text.replace(/[ \t]+\n/g, '\n');
    text = text.replace(/\n[ \t]+/g, '\n');
    text = text.replace(/\n{3,}/g, '\n\n');
    text = text.replace(/^\n+/, '');
    text = text.replace(/\n+$/, '');

    // --- Fix run-together header lines (e.g., "**HEADER** Job Series: ...") ---
    sectionHeaders.forEach(header => {
        const regex = new RegExp(`(\\*\\*${header}\\*\\*)\\s*([^\\n]+)`, 'g');
        text = text.replace(regex, '$1\n$2');
    });

    // --- Remove any double spaces ---
    text = text.replace(/ +/g, ' ');

    return text.trim();
}

window.formatGeneratedPD = fixPDFormatting;

// Keep your existing formatGeneratedPD function but with ONLY these 3 fixes:
// Comprehensive PD formatting function with aggressive spacing fixes
window.formatGeneratedPD = function(pdText) {
    if (!pdText) return '';
    
    let text = pdText.replace(/\r\n/g, '\n').trim();

    // ===== STEP 1: Fix run-together HEADER fields =====
    text = text.replace(/(Job Series:\s*GS-\d+)([A-Z][a-z])/gi, '$1\n\n$2');
    text = text.replace(/(Job Series:\s*GS-\d+)(Position Title:)/gi, '$1\n\n$2');
    text = text.replace(/(Position Title:[^\n]{1,80}?)(Agency:)/gi, '$1\n\n$2');
    text = text.replace(/(Agency:[^\n]{1,80}?)(Organization:)/gi, '$1\n\n$2');
    text = text.replace(/(Department of [A-Z][a-z]+)([A-Z][a-z]+(?:ion|ment))/gi, '$1\n\nOrganization: $2');
    text = text.replace(/(Organization:[^\n]{1,80}?)(Lowest Organization:)/gi, '$1\n\n$2');
    text = text.replace(/(Lowest Organization:[^\n]{1,80}?)(Supervisory Level:)/gi, '$1\n\n$2');
    text = text.replace(/(Supervisory Level:[^\n]{1,80}?)(Grade:)/gi, '$1\n\n$2');
    text = text.replace(/(Grade:\s*GS-\d+)(\*\*[A-Z])/gi, '$1\n\n$2');

    // ===== STEP 2: Fix MALFORMED FACTOR HEADERS (CRITICAL) =====
    // Fix "Level 1-7125, 0 Points" -> "Level 1-7, 1250 Points"
    text = text.replace(/Level\s*(\d)-(\d)(\d{3,4}),\s*\d+\s*Points/g, 'Level $1-$2, $3 Points');
    text = text.replace(/Level\s*(\d+)-(\d)(\d{3,4}),\s*\d+\s*Points/g, 'Level $1-$2, $3 Points');

    // ===== STEP 3: Format section headers =====
    const sectionHeaders = [
        'HEADER', 'INTRODUCTION', 'MAJOR DUTIES',
        'FACTOR EVALUATION - COMPLETE ANALYSIS', 'FACTOR EVALUATION',
        'EVALUATION SUMMARY', 'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ];
    
    sectionHeaders.forEach(header => {
        const pattern = new RegExp('\\*{0,2}\\s*' + header + '\\s*:?\\s*\\*{0,2}', 'gi');
        text = text.replace(pattern, `\n\n**${header}**\n\n`);
        
        // Fix headers running into content
        const fixPattern = new RegExp(`(\\*\\*${header}\\*\\*)([A-Z][a-z])`, 'g');
        text = text.replace(fixPattern, '$1\n\n$2');
    });

    // ===== STEP 4: Format factor headers =====
    text = text.replace(
        /\*{0,2}\s*Factor\s*(\d+)\s*[–-]\s*([^\n*]+?)\s*Level\s*(\d+)-(\d+),\s*(\d{1,4})\s*Points\s*\*{0,2}/gm,
        '\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n'
    );

    // ===== STEP 5: Fix evaluation summary =====
    text = text.replace(/\*{0,2}\s*Total Points:\s*(\d+)\s*\*{0,2}/gm, '\n\n**Total Points:** $1\n\n');
    text = text.replace(/\*{0,2}\s*Final Grade:\s*(GS-\d+)\s*\*{0,2}/gm, '**Final Grade:** $1\n\n');
    text = text.replace(/\*{0,2}\s*Grade Range:\s*([\d\-+]+)\s*\*{0,2}/gm, '**Grade Range:** $1\n\n');

    // ===== STEP 6: Cleanup =====
    text = text.replace(/\n{4,}/g, '\n\n\n');
    text = text.replace(/[ \t]+\n/g, '\n');
    text = text.replace(/\n[ \t]+/g, '\n');
    text = text.replace(/ {2,}/g, ' ');
    text = text.replace(/^\n+/, '');
    text = text.replace(/\n+$/, '');

    return text.trim();
};

// Your markdown to HTML converter (unchanged)
function markdownToHTML(text) {
    return text
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n\n/g, '</p><p>')
        .replace(/^(.*)$/s, '<p>$1</p>')
        .replace(/(?<!<\/p>)\n(?!<p>)/g, '<br>')
        .replace(/<p><\/p>/g, '')
        .replace(/<p><p>/g, '<p>')
        .replace(/<\/p><\/p>/g, '</p>');
}

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
        const formattedText = window.formatGeneratedPD(generatedText);
        const htmlContent = markdownToHTML(formattedText);
        finalPDElement.innerHTML = htmlContent;
        
        finalPDElement.style.cssText += `
            font-size: 14px;
            color: #374151;
        `;
        
        const strongElements = finalPDElement.querySelectorAll('strong');
        strongElements.forEach(el => {
            el.style.cssText = `
                color: #111827;
                font-weight: 600;
                display: block;
                margin-top: 1rem;
                margin-bottom: 0.5rem;
            `;
        });
        
    } else {
        finalPDElement.innerHTML = '<div style="color: #6b7280; font-style: italic; text-align: center; padding: 2rem;">No position description has been generated yet.</div>';
    }
    
    setupPDExportButtons();
}

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
                                    <i class="fas fa-file-alt" style="color: #3b82f6; font-size: 1.5rem;"></i>
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
                                <div class="upload-method-icon sample" style="display: flex; align-items: center; justify-content: center; width: 48px; height: 48px; background: #f3f4f6; border-radius: 12px; margin-right: 1rem;">
                                    <i class="fas fa-clipboard-list" style="color: #f59e42; font-size: 2rem;"></i>
                                </div>
                                <div style="flex: 1;">
                                    <h3 class="upload-method-title">Use Sample PD</h3>
                                    <p class="upload-method-description">Try with a sample General Engineer position</p>
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

    const sortedAgencyData = agencyData.slice().sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
    );

    content.innerHTML = `
        <div class="card" style="max-width: 72rem; margin: 0 auto;">
            <div class="card-header">
                <div class="card-title" style="display: flex; align-items: center; gap: 6px;">
                    <i class="fas fa-building"></i> Agency & Organization Selection
                    <span class="step-info" style="position:relative;">
                        <i class="fas fa-info-circle" style="color:#2563eb; margin-left:8px; cursor:help;"></i>
                        <div class="step-tooltip">
                            Select your department or agency, sub-organization, and lowest level office/division. All three are required to continue.
                        </div>
                    </span>
                </div>
                <div class="card-description">Select the department/agency and sub-organization</div>
            </div>
            <div class="card-content" style="max-width: 64rem; margin: 0 auto;">
                <div class="form-section">
                    <div class="form-row">
                        <div class="form-group form-group-half">
                            <label class="form-label" for="federalAgencyDropdown">Department or Agency<span style="color:#ef4444;">*</span></label>
                            <div class="dropdown-wrapper" style="position:relative;">
                                <input class="form-input" id="federalAgencyDropdown" autocomplete="off" placeholder="Search or select department or agency..." value="${formState.federalAgency || ''}">
                                <div class="dropdown-list" id="federalAgencyDropdownList" style="display:none; position:absolute; z-index:10; width:100%; background:#fff; border:1px solid #d1d5db; border-radius:0.5em; max-height:200px; overflow-y:auto;"></div>
                            </div>
                        </div>
                        <div class="form-group form-group-half">
                            <label class="form-label" for="subOrgDropdown">Sub-Organization<span style="color:#ef4444;">*</span></label>
                            <div class="dropdown-wrapper" style="position:relative;">
                                <input class="form-input" id="subOrgDropdown" autocomplete="off" placeholder="Search or select sub-organization..." value="${formState.subOrg || ''}" ${!formState.federalAgency ? 'disabled' : ''}>
                                <div class="dropdown-list" id="subOrgDropdownList" style="display:none; position:absolute; z-index:10; width:100%; background:#fff; border:1px solid #d1d5db; border-radius:0.5em; max-height:200px; overflow-y:auto;"></div>
                            </div>
                        </div>
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="lowestOrgInput">Lowest Level Organization/Office<span style="color:#ef4444;">*</span></label>
                        <input
                            class="form-input"
                            id="lowestOrgInput"
                            placeholder="Enter your specific office, division, or unit (e.g., 'Criminal Division - Fraud Section')"
                            value="${formState.lowestOrg || ''}"
                        >
                    </div>
                </div>
                <div class="step-actions" style="display: flex; justify-content: space-between; align-items: center; margin-top: 2rem;">
                    <button class="btn btn-outline" id="previousStepBtn" style="margin-right:auto;">
                        <i class="fas fa-arrow-left"></i> Previous
                    </button>
                    <button class="btn btn-primary" id="continueAgencyBtn" style="margin-left:auto;">
                        Next: Job Series Selection
                    </button>
                </div>
            </div>
        </div>
    `;

    document.getElementById('previousStepBtn').onclick = function() {
        renderStep(1); // Go back to Action Selection
    };

    // Setup dropdowns with autocomplete and consistent dropdown styling
    const federalAgencyInput = document.getElementById('federalAgencyDropdown');
    const federalAgencyList = document.getElementById('federalAgencyDropdownList');
    const subOrgInput = document.getElementById('subOrgDropdown');
    const subOrgList = document.getElementById('subOrgDropdownList');
    const continueBtn = document.getElementById('continueAgencyBtn');
    const lowestOrgInput = document.getElementById('lowestOrgInput');

    // --- Agency dropdown logic (sorted alphabetically) ---
    function showAgencyList() {
        const term = federalAgencyInput.value.trim().toLowerCase();
        const filtered = sortedAgencyData.filter(a => a.name.toLowerCase().includes(term));
        federalAgencyList.innerHTML = filtered.length
            ? filtered.map(a => `<div class="dropdown-item" data-value="${a.name}">${a.name}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        federalAgencyList.style.display = 'block';
    }
    federalAgencyInput.addEventListener('focus', showAgencyList);
    federalAgencyInput.addEventListener('input', showAgencyList);
    federalAgencyInput.addEventListener('blur', () => setTimeout(() => federalAgencyList.style.display = 'none', 150));
    federalAgencyList.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            federalAgencyInput.value = e.target.dataset.value;
            formState.federalAgency = e.target.dataset.value;
            federalAgencyList.style.display = 'none';
            // Reset sub-org
            formState.subOrg = '';
            subOrgInput.value = '';
            subOrgInput.disabled = false;
            showSubOrgList();
            updateContinueButton();
        }
    });

    // --- Sub-org dropdown logic (sorted alphabetically) ---
    function showSubOrgList() {
        const agency = sortedAgencyData.find(a => a.name === formState.federalAgency);
        const term = subOrgInput.value.trim().toLowerCase();
        // Sort subOrgs alphabetically
        const subOrgs = agency ? agency.subOrgs.slice().sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' })) : [];
        const filtered = subOrgs.filter(s => s.toLowerCase().includes(term));
        subOrgList.innerHTML = filtered.length
            ? filtered.map(s => `<div class="dropdown-item" data-value="${s}">${s}</div>`).join('')
            : `<div class="dropdown-item disabled">No results found</div>`;
        subOrgList.style.display = 'block';
    }
    subOrgInput.addEventListener('focus', showSubOrgList);
    subOrgInput.addEventListener('input', showSubOrgList);
    subOrgInput.addEventListener('blur', () => setTimeout(() => subOrgList.style.display = 'none', 150));
    subOrgList.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            subOrgInput.value = e.target.dataset.value;
            formState.subOrg = e.target.dataset.value;
            subOrgList.style.display = 'none';
            updateContinueButton();
        }
    });

    lowestOrgInput.addEventListener('input', function() {
        formState.lowestOrg = this.value;
        updateContinueButton();
    });

    function updateContinueButton() {
        const canContinue = (
            federalAgencyInput.value &&
            subOrgInput.value &&
            lowestOrgInput.value.trim() !== ''
        );
        continueBtn.disabled = !canContinue;
        continueBtn.classList.toggle('btn-disabled', !canContinue);
        continueBtn.style.opacity = canContinue ? '1' : '0.6';
        continueBtn.style.cursor = canContinue ? 'pointer' : 'not-allowed';
    }

    continueBtn.addEventListener('click', function() {
        if (!this.disabled) {
            currentStep = 3;
            renderSidebar(currentStep);
            renderStep(3);
        }
    });

    // If agency is already selected, populate sub-orgs
    if (formState.federalAgency) {
        const agency = sortedAgencyData.find(a => a.name === formState.federalAgency);
        if (agency) {
            subOrgInput.disabled = false;
            subOrgInput.value = formState.subOrg || '';
            showSubOrgList();
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

    list.addEventListener('mousedown', async function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.textContent;
            formState.jobSeries = e.target.dataset.value;
            list.style.display = 'none';

            // Clear position title values from form state
            formState.positionTitle = '';
            formState.positionTitles = [];

            // Immediately re-render the position title field to show the dropdown
            await renderPositionTitleField();
            
            // Run validation check
            if (typeof window.checkComplete === 'function') {
                window.checkComplete();
            }
        }
    });
}

function setupClassificationValidation() {
    const continueBtn = document.getElementById('continueClassificationBtn');
    const groupSeriesInput = document.getElementById('groupSeriesDropdown');
    const jobSeriesInput = document.getElementById('jobSeriesDropdown');
    const positionTitleInput = document.getElementById('positionTitleInput');
    const orgTitleInput = document.getElementById('organizationalTitleInput');

    continueBtn.disabled = true;
    continueBtn.style.opacity = '0.6';
    continueBtn.style.cursor = 'not-allowed';

    function checkComplete() {
        const groupSeriesFilled = groupSeriesInput.value.trim() !== '';
        const jobSeriesFilled = jobSeriesInput.value.trim() !== '';
        const positionTitleFilled = positionTitleInput && positionTitleInput.value.trim() !== '';
        const orgTitleFilled = orgTitleInput && orgTitleInput.value.trim() !== '';

        // Allow continue if:
        // (Group Series, Job Series, and Position Title) are filled
        // OR
        // (Group Series, Job Series, Position Title, and Organizational Title) are all filled
        if (
            (groupSeriesFilled && jobSeriesFilled && positionTitleFilled) ||
            (groupSeriesFilled && jobSeriesFilled && positionTitleFilled && orgTitleFilled)
        ) {
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
    if (positionTitleInput) {
        positionTitleInput.addEventListener('input', function() {
            formState.positionTitle = this.value;
            checkComplete();
        });
    }
    if (orgTitleInput) {
        orgTitleInput.addEventListener('input', function() {
            formState.organizationalTitle = this.value;
            checkComplete();
        });
    }
    checkComplete();

    continueBtn.onclick = function() {
        if (!continueBtn.disabled) {
            if (formState.jobSeries === '0301') {
                formState.positionTitle = positionTitleInput.value;
            }
            if (formState.usedAIRecommendation) {
                renderStep(5);
            } else {
                renderStep(4);
            }
        }
    };
}

function setupUnknownSeriesLogic() {
    // Ensure at least 6 unknown duties
    if (!formState.unknownDuties || formState.unknownDuties.length < 6) {
        formState.unknownDuties = Array(6).fill('');
    }

    // Always render numbered inputs
    renderDutyInputs(formState.unknownDuties);

    // Re-enable AI Recommendation button
    const getAIRecBtn = document.getElementById('getAIRecommendationBtn');
    if (getAIRecBtn) {
        getAIRecBtn.disabled = false;
        getAIRecBtn.style.opacity = '1';
        getAIRecBtn.style.cursor = 'pointer';
        getAIRecBtn.classList.remove('btn-disabled');
    }

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

        // Always update formState.unknownDuties
        formState.unknownDuties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);

        if (duties.length >= 6) {
            getAIRecBtn.disabled = false;
            getAIRecBtn.style.opacity = '1';
            getAIRecBtn.style.cursor = 'pointer';
            getAIRecBtn.classList.remove('btn-disabled');
        } else {
            getAIRecBtn.disabled = true;
            getAIRecBtn.style.opacity = '0.6';
            getAIRecBtn.style.cursor = 'not-allowed';
            getAIRecBtn.classList.add('btn-disabled');
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
        // --- FIX: Copy duties to formState.duties before moving to next step ---
        formState.duties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);
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
    // Deduplicate recommendations by code+title (case-insensitive)
    let recs = (data.recommendations || []).filter(Boolean);
    const seen = new Set();
    recs = recs.filter(rec => {
        const key = (rec.code || '').trim() + '|' + (rec.title || '').trim().toLowerCase();
        if (seen.has(key) || !rec.code) return false;
        seen.add(key);
        return true;
    }).slice(0, 3);

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
    setTimeout(() => renderDutiesGradeAnalysis(data), 100);
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

    // Show Title and Series Determination section IMMEDIATELY after grade analysis
    showTitleSeriesAboveGradeAnalysis(
        (formState.duties || []).join('\n'),
        data.gsGrade || formState.gsGrade || '',
        jobSeriesCode,
        positionTitle,
        formState.supervisoryLevel
    );

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
    const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
    const newDuties = (formState.refineDuties || []).filter(d => d.trim());
    const allDuties = [...oldDuties, ...newDuties].join('\n');
    
    // Fix: Define jobSeriesCode and other variables from formState or data
    const jobSeriesCode = formState.jobSeries || '';
    const jobSeriesTitle = formState.positionTitle || '';
    const positionTitle = formState.positionTitle || '';
    
    const payload = {
        duties: allDuties,
        gsGrade: data.gsGrade || formState.gsGrade || '',
        jobSeries: jobSeriesCode,
        jobTitle: jobSeriesTitle,
        positionTitle: positionTitle,
        supervisoryLevel: formState.supervisoryLevel
    };
    
    const evalResultDiv = document.getElementById('evaluationStatementResult');
    evalResultDiv.innerHTML = `
        <div class="eval-scrollbox">
            <span class="spinner"></span> Generating evaluation statement...
            <div id="evalStatementText" style="margin-top:1em; white-space:pre-line; color:#374751;"></div>
        </div>
    `;
    
    let fullText = '';
    try {
        const response = await fetch('/api/generate-evaluation-statement', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        
        const reader = response.body.getReader();
        let decoder = new TextDecoder();
        let buffer = '';
        
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let lines = buffer.split('\n');
            buffer = lines.pop();
            
            for (let line of lines) {
                if (line.startsWith('data: ')) {
                    try {
                        const data = JSON.parse(line.slice(6));
                        if (data.response) {
                            fullText += data.response;
                            document.getElementById('evalStatementText').textContent = fullText;
                        }
                        if (data.evaluationStatement) {
                            fullText = data.evaluationStatement;
                            document.getElementById('evalStatementText').textContent = fullText;
                        }
                    } catch (e) {}
                }
            }
        }
        
        // Final render with export buttons
        evalResultDiv.innerHTML = `
            <div class="eval-scrollbox">
                <h4 style="font-weight: 600; color: #111827; margin-bottom: 0.75rem;">AI Evaluation Statement</h4>
                <div id="evalStatementText" style="white-space:pre-line; color:#374151;">${fullText || 'No statement generated.'}</div>
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
        setupEvalStatementExportButtons(fullText);
    } catch (err) {
        evalResultDiv.innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
    }
};
    // Setup refine grade UI
    setupRefineGradeAnalysisUI();
}

// In setupDutiesValidation, update the continue button handler to always save duties before moving to the next step
function setupDutiesValidation() {
    const gradeBtn = document.getElementById('gradeAnalysisBtn');
    const continueBtn = document.getElementById('continueDutiesBtn');
    gradeBtn.style.display = '';
    continueBtn.style.display = 'none';

    function checkComplete() {
        const duties = Array.from(document.querySelectorAll('.duty-input'))
            .map(input => input.value.trim())
            .filter(duty => duty.length > 0);

        // Always update formState.duties with current input values
        formState.duties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);

        if (duties.length >= 6) {
            gradeBtn.disabled = false;
            gradeBtn.style.opacity = '1';
            gradeBtn.style.cursor = 'pointer';
            gradeBtn.classList.remove('btn-disabled');
        } else {
            gradeBtn.disabled = true;
            gradeBtn.style.opacity = '0.6';
            gradeBtn.style.cursor = 'not-allowed';
            gradeBtn.classList.add('btn-disabled');
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
        gradeBtn.style.opacity = '0.6';
        gradeBtn.style.cursor = 'not-allowed';
        document.getElementById('gradeAnalysisResult').innerHTML = '<span class="spinner"></span> Analyzing grade level...';

        try {
            const response = await fetch('/api/recommend-series', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ duties: duties.join('\n') })
            });

            if (!response.ok) throw new Error('Server error');
            const data = await response.json();

            renderDutiesGradeAnalysis({
                recommendations: Array.isArray(data.recommendations) ? data.recommendations : [],
                gradeRelevancy: Array.isArray(data.gradeRelevancy) ? data.gradeRelevancy : [],
                gsGrade: data.gsGrade || null
            });
            continueBtn.style.display = '';

        } catch (err) {
            document.getElementById('gradeAnalysisResult').innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
            gradeBtn.disabled = false;
            gradeBtn.style.opacity = '1';
            gradeBtn.style.cursor = 'pointer';
        }
    };

    continueBtn.onclick = function() {
        // --- FIX: Save duties before moving to Review & Generate step ---
        formState.duties = Array.from(document.querySelectorAll('.duty-input')).map(input => input.value);
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

    // Only valid GS grades (no GS-6, GS-8, GS-10)
    const validGrades = [5, 7, 9, 11, 12, 13, 14, 15];
    const gradeNum = parseInt(recommendedGrade.replace(/[^0-9]/g, ''), 10);

    // Helper to get adjacent valid GS grades
    function getAdjacentGSGrades(num) {
        const idx = validGrades.indexOf(num);
        if (idx === -1) return [`GS-${num}`];
        let grades = [];
        if (idx > 0) grades.push(`GS-${validGrades[idx - 1]}`);
        grades.push(`GS-${validGrades[idx]}`);
        if (idx < validGrades.length - 1) grades.push(`GS-${validGrades[idx + 1]}`);
        return grades;
    }

    let gradeData = [];
    if (Array.isArray(data.gradeRelevancy) && data.gradeRelevancy.length > 0) {
        const adjacentGrades = getAdjacentGSGrades(gradeNum);
        gradeData = data.gradeRelevancy
            .filter(item => adjacentGrades.includes(item.grade))
            .filter(item => {
                // Exclude GS-6, GS-8, GS-10
                const n = parseInt(item.grade.replace(/[^0-9]/g, ''), 10);
                return validGrades.includes(n);
            });
        adjacentGrades.forEach(g => {
            if (!gradeData.some(item => item.grade === g)) {
                const n = parseInt(g.replace(/[^0-9]/g, ''), 10);
                if (validGrades.includes(n)) {
                    gradeData.push({ grade: g, percentage: 0 });
                }
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
        const adjacentGrades = getAdjacentGSGrades(gradeNum);
        gradeData = adjacentGrades
            .filter(g => {
                const n = parseInt(g.replace(/[^0-9]/g, ''), 10);
                return validGrades.includes(n);
            })
            .map((g, i) => ({
                grade: g,
                percentage: i === 1 ? 60 : (i === 0 ? 30 : 10)
            }));
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

    // Show Title and Series Determination section immediately after grade analysis
    showTitleSeriesAboveGradeAnalysis(
        (formState.duties || []).join('\n'),
        formState.gsGrade,
        formState.jobSeries,
        formState.positionTitle,
        formState.supervisoryLevel
    );

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
        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        const payload = { duties: allDuties, supervisoryLevel: formState.supervisoryLevel || '' };
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

    document.getElementById('generateEvalStatementBtn').onclick = async function() {
        const btn = this;
        btn.disabled = true;
        btn.textContent = 'Generating...';

        const evalResultDiv = document.getElementById('evaluationStatementResult');
        evalResultDiv.innerHTML = `
            <div class="loading"><span class="spinner"></span> Generating PD with AI...</div>
        `;

        const oldDuties = Array.isArray(formState.duties) ? formState.duties.filter(d => d.trim()) : [];
        const newDuties = (formState.refineDuties || []).filter(d => d.trim());
        const allDuties = [...oldDuties, ...newDuties].join('\n');
        const jobSeriesCode = formState.jobSeries || '';
        const jobSeriesTitle = formState.positionTitle || '';
        const positionTitle = formState.positionTitle || '';
        const payload = {
            duties: allDuties,
            gsGrade: formState.gsGrade || '',
            jobSeries: jobSeriesCode,
            jobTitle: jobSeriesTitle,
            positionTitle: positionTitle,
            supervisoryLevel: formState.supervisoryLevel
        };

        let fullText = '';
        try {
            const response = await fetch('/api/generate-evaluation-statement', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            const reader = response.body.getReader();
            let decoder = new TextDecoder();
            let buffer = '';

            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                let lines = buffer.split('\n');
                buffer = lines.pop();

                for (let line of lines) {
                    if (line.startsWith('data: ')) {
                        try {
                            const data = JSON.parse(line.slice(6));
                            if (data.response) {
                                fullText += data.response;
                                document.getElementById('evalStatementText').textContent = fullText;
                            }
                            if (data.evaluationStatement) {
                                fullText = data.evaluationStatement;
                                document.getElementById('evalStatementText').textContent = fullText;
                            }
                        } catch (e) {}
                    }
                }
            }

            // Final render with export buttons
            evalResultDiv.innerHTML = `
                <div class="eval-scrollbox">
                    <h4 style="font-weight: 600; color: #111827; margin-bottom: 0.75rem;">AI Evaluation Statement</h4>
                    <div id="evalStatementText" style="white-space:pre-line; color:#374151;">${fullText || 'No statement generated.'}</div>
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
            setupEvalStatementExportButtons(fullText);
        } catch (err) {
            evalResultDiv.innerHTML = `<div class="alert alert-warning">Error: ${err.message}</div>`;
        } finally {
            btn.disabled = false;
            btn.textContent = 'Evaluation Statement';
        }
    };
}

// Duty management functions
window.addDuty = function() {
    const container = document.getElementById('dutiesContainer');
    // Only count actual duty input groups (not the clear button)
    const dutyCount = container.querySelectorAll('.duty-input-group').length;
    if (dutyCount >= 20) return;

    const dutyGroup = document.createElement('div');
    dutyGroup.className = 'duty-input-group';
    dutyGroup.innerHTML = `
        <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;">${dutyCount + 1}.</span>
        <input type="text" class="duty-input" placeholder="Duty/Responsibility ${dutyCount + 1}...">
        <button type="button" class="remove-duty" onclick="removeDuty(this)">×</button>
    `;
    container.appendChild(dutyGroup);
    updateDutyNumbers();
    updateDutyCounter();
};

// Helper to renumber all duty inputs after add/remove
function updateDutyNumbers() {
    const container = document.getElementById('dutiesContainer');
    const groups = container.querySelectorAll('.duty-input-group');
    groups.forEach((group, idx) => {
        const numberSpan = group.querySelector('.duty-number');
        const input = group.querySelector('.duty-input');
        if (numberSpan) numberSpan.textContent = `${idx + 1}.`;
        if (input) input.placeholder = `Duty/Responsibility ${idx + 1}...`;
    });
}

window.removeDuty = function(button) {
    const group = button.closest('.duty-input-group');
    if (group) {
        group.remove();
        updateDutyNumbers();
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

        // Always use the user's selection from formState
        const jobSeries = formState.jobSeries || '';
        const positionTitle = formState.positionTitle || '';
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

        const payload = {
            jobSeries: jobSeries,
            positionTitle: positionTitle,
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
            await streamPDGeneration(payload); // <-- Use non-streaming
        } catch (err) {
            aiResult.innerHTML = `
                <div class="alert alert-warning">
                    <strong>Error:</strong> ${err.message}<br>
                    <details style="margin-top: 0.5rem;">
                        <summary>Debug Info</summary>
                        <pre style="font-size: 0.8rem; background: #f5f5f5; padding: 0.5rem; margin-top: 0.5rem;">
Check browser console for detailed logs.
Make sure your backend is returning properly formatted response.
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
        aiResult.innerHTML = '<div class="alert alert-danger">No response from server.</div>';
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
            if (line.trim().startsWith('data:')) {
                try {
                    const data = JSON.parse(line.trim().substring(5));
                    if (data.response) {
                        fullPD += data.response;
                    }
                } catch (e) {
                    // Ignore parse errors
                }
            }
        }
    }

    // After streaming is done, format and display the full PD
    if (fullPD.trim()) {
        const formatted = window.formatGeneratedPD(fullPD);
        aiResult.innerHTML = window.markdownToHTML(formatted);
        window.generatedPD = formatted;
        renderStep(6); // Advance to the Generated PD step
    } else {
        aiResult.innerHTML = '<div class="alert alert-danger">No content generated. Please try again.</div>';
    }
}

// Alternative non-streaming approach if streaming continues to fail
async function tryNonStreamingGeneration(payload) {
    const aiResult = document.getElementById('aiResult');
    aiResult.innerHTML = '<div class="loading"><span class="spinner"></span> Generating PD with AI...</div>';

    const response = await fetch('/api/generate-sync', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    const data = await response.json();
    if (data.fullPD) {
        window.generatedPD = data.fullPD;
        renderStep(6); // Show the generated PD step
    } else {
        aiResult.innerHTML = `<div style="color:red;">Error: ${data.error || 'Failed to generate PD.'}</div>`;
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
    
    console.log('pdSections keys before review formatting:', Object.keys(pdSections));
    
    // Debug: Check for summary sections in pdSections
    const summaryKeys = Object.keys(pdSections).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary section keys found in pdSections:', summaryKeys);
    summaryKeys.forEach(key => {
        console.log(`  ${key}: content length = ${(pdSections[key] || '').length}`);
    });
    
    const cleanedSections = cleanupMajorDutyDuplicates({ ...pdSections });
    console.log('Cleaned sections for review:', Object.keys(cleanedSections));
    
    const sectionContentsOnly = getSectionContentsOnly(cleanedSections);
    console.log('Section contents only:', Object.keys(sectionContentsOnly));
    
    const summaryKeysAfter = Object.keys(sectionContentsOnly).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary section keys after processing:', summaryKeysAfter);
    
    const cleanContent = formatUpdatedPD(sectionContentsOnly);
    console.log('Formatted content length:', cleanContent.length);
    console.log('Formatted content preview (first 500 chars):', cleanContent.substring(0, 500));
    console.log('Formatted content preview (last 500 chars):', cleanContent.substring(cleanContent.length - 500));
    
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

function displayReviewContent() {
    console.log('=== DISPLAY REVIEW CONTENT ===');
    const reviewTextElement = document.getElementById('reviewText');
    if (!reviewTextElement) {
        console.log('reviewText element not found');
        return;
    }
    
    console.log('pdSections keys before review formatting:', Object.keys(pdSections));
    
    // Debug: Check for summary sections
    const summaryKeys = Object.keys(pdSections).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary section keys found:', summaryKeys);
    
    // Clean up pdSections one more time before formatting
    const cleanedSections = cleanupMajorDutyDuplicates({ ...pdSections });
    console.log('Cleaned sections for review:', Object.keys(cleanedSections));
    
    const sectionContentsOnly = getSectionContentsOnly(cleanedSections);
    console.log('Section contents only:', Object.keys(sectionContentsOnly));
    
    // Debug: Check for summary sections after processing
    const summaryKeysAfter = Object.keys(sectionContentsOnly).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary section keys after processing:', summaryKeysAfter);
    
    const cleanContent = formatUpdatedPD(sectionContentsOnly);
    console.log('Formatted content length:', cleanContent.length);
    console.log('Formatted content preview:', cleanContent.substring(0, 500));
    
    reviewTextElement.textContent = cleanContent;
    console.log('=== END DISPLAY REVIEW CONTENT ===');
}

function formatUpdatedPD(sectionsObject) {
    console.log('=== FORMAT UPDATED PD ===');
    console.log('Input sections:', Object.keys(sectionsObject));
    
    const dedupedSections = cleanupMajorDutyDuplicates({ ...sectionsObject });
    const cleanedSections = removeDuplicateSummarySections(dedupedSections);
    
    console.log('After cleanup:', Object.keys(cleanedSections));
    
    // Debug: Check for summary sections with detailed info
    const summaryKeys = Object.keys(cleanedSections).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary sections in cleanedSections:', summaryKeys);
    summaryKeys.forEach(key => {
        console.log(`  ${key}:`);
        console.log(`    - exists: ${!!cleanedSections[key]}`);
        console.log(`    - type: ${typeof cleanedSections[key]}`);
        console.log(`    - content: "${cleanedSections[key]}"`);
        console.log(`    - length: ${(cleanedSections[key] || '').length}`);
    });

    const sectionOrder = [
        'HEADER', 'INTRODUCTION', 'MAJOR DUTIES',
        ...Array.from({length: 9}, (_, i) => `Factor ${i+1}`),
        'Total Points', 'Final Grade', 'Grade Range',
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ];

    let formatted = '';

    sectionOrder.forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        let content = key ? cleanedSections[key] : '';
        
        if (sectionKey === 'Total Points' || sectionKey === 'Final Grade' || sectionKey === 'Grade Range') {
            console.log(`Processing summary section: ${sectionKey}`);
            console.log(`  - found key: ${key}`);
            console.log(`  - content: "${content}"`);
        }
        
        // Special handling for summary sections - ALWAYS show them
        if (key && (sectionKey === 'Total Points' || sectionKey === 'Final Grade' || sectionKey === 'Grade Range')) {
            console.log(`  - ADDING summary section: ${key}`);
            formatted += `\n\n**${key}**\n\n`;
        } else if (key && content && content.trim()) {
            // HEADER: each variable on its own line
            if (sectionKey === 'HEADER') {
                content = content
                    .replace(/Job Series:\s*/i, 'Job Series: ')
                    .replace(/Position Title:\s*/i, '\nPosition Title: ')
                    .replace(/Agency:\s*/i, '\nAgency: ')
                    .replace(/Organization:\s*/i, '\nOrganization: ')
                    .replace(/Lowest Organization:\s*/i, '\nLowest Organization: ')
                    .replace(/Supervisory Level:\s*/i, '\nSupervisory Level: ')
                    .replace(/\n{2,}/g, '\n')
                    .trim();
            }
            if (sectionKey === 'MAJOR DUTIES') {
                content = content.replace(/(\d+\.\s)/g, '\n$1').replace(/\n{2,}/g, '\n').trim();
            }
            formatted += `\n\n**${key}**\n\n${content}\n`;
        }
    });

    console.log('After ordered sections, checking remaining...');
    
    // Add any remaining sections not in the preferred order
    Object.keys(cleanedSections).forEach(key => {
        const alreadyRendered = sectionOrder.some(section => findSectionKeyFlexible(cleanedSections, section) === key);
        const isSummarySection = /^Total Points:/i.test(key) || /^Final Grade:/i.test(key) || /^Grade Range:/i.test(key);
        
        if (isSummarySection) {
            console.log(`Remaining section check - Summary: ${key}, already rendered: ${alreadyRendered}`);
        }
        
        if (!alreadyRendered) {
            if (isSummarySection) {
                console.log(`Adding remaining summary section: ${key}`);
                formatted += `\n\n**${key}**\n\n`;
            } else if (cleanedSections[key] && cleanedSections[key].trim()) {
                formatted += `\n\n**${key}**\n\n${cleanedSections[key].trim()}\n`;
            }
        }
    });

    formatted = formatted.replace(/\n{3,}/g, '\n\n').trim();
    
    console.log('Final formatted length:', formatted.length);
    console.log('=== END FORMAT UPDATED PD ===');

    return formatted;
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

function findSectionKeyFlexible(sections, targetSection) {
    const keys = Object.keys(sections);
    
    const exactMatch = keys.find(key => key === targetSection);
    if (exactMatch) return exactMatch;
    
    const caseInsensitiveMatch = keys.find(key => 
        key.toUpperCase() === targetSection.toUpperCase()
    );
    if (caseInsensitiveMatch) return caseInsensitiveMatch;
    
    // Special handling for summary sections - match by prefix
    if (targetSection === 'Total Points' || targetSection === 'Final Grade' || targetSection === 'Grade Range') {
        const summaryMatch = keys.find(key => {
            const keyUpper = key.toUpperCase();
            const targetUpper = targetSection.toUpperCase();
            return keyUpper.startsWith(targetUpper + ':') || keyUpper === targetUpper;
        });
        if (summaryMatch) return summaryMatch;
    }
    
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

// Track edit history for each section
if (!window.sectionEditHistory) window.sectionEditHistory = {};

function createSectionDiv(title, content) {
    const safeTitle = title.replace(/[\n\r"]/g, ' ').replace(/\s+/g, ' ').trim();
    const isSummarySection = /^Total Points:|^Final Grade:|^Grade Range:/i.test(safeTitle);
    const isNonEditable = (
        safeTitle.toUpperCase().includes('FACTOR EVALUATION COMPLETE ANALYSIS') ||
        safeTitle.toUpperCase().includes('FACTOR EVALUATION - COMPLETE ANALYSIS')
    );

    const sectionDiv = document.createElement('div');
    sectionDiv.className = 'editor-section default';
    sectionDiv.setAttribute('data-section-title', safeTitle);

    // Render summary sections as non-editable, no asterisks, header only
    if (isSummarySection) {
        let value = '';
        if (/^Total Points:/i.test(safeTitle)) {
            value = safeTitle.replace(/^Total Points:\s*/i, '');
            sectionDiv.innerHTML = `<div class="section-header"><span class="section-title-label" style="font-weight:700; font-size:1.1em;">Total Points: ${value}</span></div>`;
        } else if (/^Final Grade:/i.test(safeTitle)) {
            value = safeTitle.replace(/^Final Grade:\s*/i, '');
            sectionDiv.innerHTML = `<div class="section-header"><span class="section-title-label" style="font-weight:700; font-size:1.1em;">Final Grade: ${value}</span></div>`;
        } else if (/^Grade Range:/i.test(safeTitle)) {
            value = safeTitle.replace(/^Grade Range:\s*/i, '');
            sectionDiv.innerHTML = `<div class="section-header"><span class="section-title-label" style="font-weight:700; font-size:1.1em;">Grade Range: ${value}</span></div>`;
        }
        return sectionDiv;
    }

    // Render non-editable factor evaluation section (no edit button)
    if (isNonEditable) {
        sectionDiv.innerHTML = `
            <div class="section-header">
                <span class="section-title-label" style="font-weight:700; font-size:1.1em;">${safeTitle}</span>
            </div>
            <div class="section-content">
                <div class="content-display" id="display-${sectionKeyToId(safeTitle)}">
                    ${cleanContent(content || '')}
                </div>
            </div>
        `;
        return sectionDiv;
    }

    // Default: editable section
    sectionDiv.innerHTML = `
        <div class="section-header">
            <div class="section-header-content">
                <span class="section-title-label" style="font-weight:700; font-size:1.1em; width:100%; margin-bottom:0.5em; display:block;">
                    ${safeTitle}
                </span>
                <button class="section-edit-button" type="button" onclick="toggleSectionEdit('${safeTitle}', this)">
                    <i class="fas fa-edit"></i>
                    Edit
                </button>
            </div>
        </div>
        <div class="section-content">
            <div class="content-display" onclick="toggleSectionEdit('${safeTitle}')" id="display-${sectionKeyToId(safeTitle)}">
                ${cleanContent(content || '') || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>'}
            </div>
            <div class="content-editor" style="display: none;" id="editor-${sectionKeyToId(safeTitle)}">
                <textarea class="editor-textarea" id="textarea-${sectionKeyToId(safeTitle)}" style="width:100%;min-height:120px;">${cleanContent(content || '')}</textarea>
                <div class="editor-actions">
                    <button class="editor-button" type="button" onclick="undoSectionEdit('${safeTitle}')">Undo</button>
                    <button class="editor-button" type="button" onclick="resetSectionEdit('${safeTitle}')">Reset Section</button>
                    <button class="editor-button" type="button" onclick="cancelSectionEdit('${safeTitle}')">Cancel</button>
                    <button class="editor-button primary" type="button" onclick="saveSectionEdit('${safeTitle}')">
                        <i class="fas fa-save"></i> Save Changes
                    </button>
                </div>
            </div>
        </div>
    `;
    return sectionDiv;
}

async function saveSectionEdit(title) {
    if (/^Total Points:|^Final Grade:|^Grade Range:/i.test(title)) {
        return;
    }

    const textarea = document.getElementById(`textarea-${sectionKeyToId(title)}`);
    if (!textarea) return;

    const display = document.getElementById(`display-${sectionKeyToId(title)}`);
    const section = display ? display.closest('.editor-section') : null;
    let newContent = cleanContent(textarea.value.trim());

    // Save edited content
    let newTitle = title;
    if (!window.sectionEditHistory[title]) window.sectionEditHistory[title] = [];
    window.sectionEditHistory[title].push({ content: newContent, header: newTitle, ts: Date.now() });
    window.sectionEditStacks[newTitle] = [newContent];

    // Always update pdSections for all edits
    pdSections[newTitle] = newContent;

    display.innerHTML = newContent || '<span style="color: #9ca3af; font-style: italic;">Click to add content...</span>';
    display.style.whiteSpace = 'pre-line';

    toggleSectionEdit(newTitle);
    section.classList.remove('modified');
    updateSaveStatus();

    // If this is a factor section, update points, headers, and grade via AI
    if (/^Factor\s*\d+/i.test(newTitle)) {
        try {
            const loadingDiv = document.createElement('div');
            loadingDiv.id = 'factor-loading';
            loadingDiv.innerHTML = '<div class="loading" style="padding: 1rem; text-align: center;"><span class="spinner"></span> Recalculating factor points and grade...</div>';
            section.appendChild(loadingDiv);

            const factorContents = {};
            for (let i = 1; i <= 9; i++) {
                const key = Object.keys(pdSections).find(k => 
                    k.match(new RegExp(`^Factor\\s*${i}`, 'i'))
                );
                if (key && pdSections[key] && pdSections[key].trim()) {
                    factorContents[`Factor ${i}`] = pdSections[key];
                }
            }

            const response = await fetch('/api/update-factor-points', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ factors: factorContents })
            });

            const loading = document.getElementById('factor-loading');
            if (loading) loading.remove();

            if (!response.ok) throw new Error(`Server error: ${response.status}`);
            const data = await response.json();
            if (data.error) throw new Error(data.error);

            // Remove all old factor keys first
            Object.keys(pdSections).forEach(key => {
                if (/^Factor\s*\d+/i.test(key)) {
                    delete pdSections[key];
                }
            });

            // Add new factor sections with header as section title
            for (let i = 1; i <= 9; i++) {
                const key = `Factor ${i}`;
                if (data.factors[key]) {
                    const factor = data.factors[key];
                    const factorName = getFactorName(i);
                    const header = `Factor ${i} - ${factorName} Level ${factor.level}, ${factor.points} Points`;
                    pdSections[header] = factor.rationale || '';
                }
            }

            // Always update summary sections
            await updateSummarySections({
                totalPoints: data.totalPoints,
                finalGrade: data.finalGrade,
                gradeRange: data.gradeRange
            });

            renderEditableSections();
            showFactorUpdateSuccess();

        } catch (err) {
            console.error('Failed to update factor points/grade:', err);
            showFactorUpdateError(err.message);
            const loading = document.getElementById('factor-loading');
            if (loading) loading.remove();
        }
    }

    // If this is the MAJOR DUTIES section, regenerate all factors and summary
    if (/^MAJOR DUTIES$/i.test(newTitle)) {
        try {
            const loadingDiv = document.createElement('div');
            loadingDiv.id = 'major-duties-loading';
            loadingDiv.innerHTML = '<div class="loading" style="padding: 1rem; text-align: center;"><span class="spinner"></span> Updating factors and grade for new major duties...</div>';
            section.appendChild(loadingDiv);

            // Replace old major duties with new content
            pdSections['MAJOR DUTIES'] = newContent;

            // Remove any other keys that match MAJOR DUTY X or MAJOR DUTIESX
            Object.keys(pdSections).forEach(key => {
                if (
                    key !== 'MAJOR DUTIES' &&
                    /^MAJOR DUT(Y|IES)\s*\d*/i.test(key.trim())
                ) {
                    delete pdSections[key];
                }
            });

            // Send only the new major duties text to backend
            const response = await fetch('/api/regenerate-factors', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ duties: newContent })
            });

            const loading = document.getElementById('major-duties-loading');
            if (loading) loading.remove();

            if (!response.ok) throw new Error(`Server error: ${response.status}`);
            const data = await response.json();
            if (data.error) {
                let msg = data.error;
                if (data.rawResponse) {
                    msg += "\n\nAI Response:\n" + data.rawResponse;
                }
                showFactorUpdateError(msg);
                return;
            }

            // Remove all old factor keys first
            Object.keys(pdSections).forEach(key => {
                if (/^Factor\s*\d+/i.test(key)) {
                    delete pdSections[key];
                }
            });

            // Add new factor sections with header as section title
            for (let i = 1; i <= 9; i++) {
                const key = `Factor ${i}`;
                if (data.factors[key]) {
                    const factor = data.factors[key];
                    const factorName = getFactorName(i);
                    const header = `Factor ${i} - ${factorName} Level ${factor.level}, ${factor.points} Points`;
                    pdSections[header] = factor.rationale || '';
                }
            }

            // Always update summary sections
            await updateSummarySections({
                totalPoints: data.totalPoints,
                finalGrade: data.finalGrade,
                gradeRange: data.gradeRange
            });

            renderEditableSections();
            showFactorUpdateSuccess();

        } catch (err) {
            console.error('Failed to update factors/grade for major duties:', err);
            showFactorUpdateError(err.message);
            const loading = document.getElementById('major-duties-loading');
            if (loading) loading.remove();
        }
    }
}
window.saveSectionEdit = saveSectionEdit;

// Helper function to update summary sections
async function updateSummarySections(data) {
    // Remove old summary sections
    Object.keys(pdSections).forEach(key => {
        if (/^Total Points:/i.test(key) || /^Final Grade:/i.test(key) || /^Grade Range:/i.test(key)) {
            delete pdSections[key];
        }
    });

    // Add new summary sections with updated values
    if (data.totalPoints !== undefined) {
        pdSections[`Total Points: ${data.totalPoints}`] = `Total Points: ${data.totalPoints}`;
    }
    if (data.finalGrade) {
        pdSections[`Final Grade: ${data.finalGrade}`] = `Final Grade: ${data.finalGrade}`;
    }
    if (data.gradeRange) {
        pdSections[`Grade Range: ${data.gradeRange}`] = `Grade Range: ${data.gradeRange}`;
    }

    // Re-render editable sections to reflect changes
    if (typeof renderEditableSections === 'function') {
        renderEditableSections();
    }
}

// Helper function to get factor names
function getFactorName(factorNum) {
    const factorNames = {
        1: "Knowledge Required by the Position",
        2: "Supervisory Controls",
        3: "Guidelines",
        4: "Complexity",
        5: "Scope and Effect",
        6: "Personal Contacts",
        7: "Purpose of Contacts",
        8: "Physical Demands",
        9: "Work Environment"
    };
    return factorNames[factorNum] || `Factor ${factorNum}`;
}

// Enhanced factor update function to handle proper header/content separation
async function updateFactorSections(factors) {
    console.log('Updating factor sections:', factors);
    
    for (let i = 1; i <= 9; i++) {
        const key = `Factor ${i}`;
        if (factors[key]) {
            const factor = factors[key];
            const factorName = getFactorName(i);
            
            // Create new section key with updated header
            const newSectionKey = `Factor ${i} - ${factorName} Level ${factor.level}, ${factor.points} Points`;
            
            // Store just the rationale content (not header info)
            pdSections[newSectionKey] = factor.rationale || '';
            
            // Remove old factor sections with different headers
            const oldKeys = Object.keys(pdSections).filter(k => 
                k.match(new RegExp(`^Factor\\s*${i}\\s*-`, 'i')) && k !== newSectionKey
            );
            oldKeys.forEach(oldKey => delete pdSections[oldKey]);
        }
    }
    
    // Re-render to show updated sections
    renderEditableSections();
}

window.saveSectionEdit = saveSectionEdit;
window.updateSummarySections = updateSummarySections;
window.updateFactorSections = updateFactorSections;
window.getFactorName = getFactorName;

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

function renderDutyInputs(dutiesArr) {
    const container = document.getElementById('dutiesContainer');
    if (!container) return;

    container.innerHTML = '';

    // Normalize incoming duties into primary + secondary
    let primary = '';
    let secondary = [];

    if (Array.isArray(dutiesArr) && dutiesArr.length > 0) {
        primary = dutiesArr[0] || '';
        secondary = dutiesArr.slice(1);
    } else if (dutiesArr && typeof dutiesArr === 'object') {
        primary = dutiesArr.primary || '';
        secondary = Array.isArray(dutiesArr.secondary) ? dutiesArr.secondary.slice() : [];
    }

    // Ensure at least 5 secondary inputs are shown initially
    const minSecondaryShown = 5;
    while (secondary.length < minSecondaryShown) secondary.push('');

    // Limit total secondaries to sensible max (20)
    const maxSecondary = 20;
    if (secondary.length > maxSecondary) secondary = secondary.slice(0, maxSecondary);

    // PRIMARY DUTY section
    container.innerHTML += `
        <div class="duty-section duty-primary" style="margin-bottom:1rem;">
            <div style="display:flex; align-items:center; gap:0.75rem; margin-bottom:0.5rem;">
                <span style="font-weight:700; color:#2563eb;">Primary Duty</span>
                <span style="color:#6b7280; font-size:0.9em;">(Core, most critical responsibility — single entry)</span>
            </div>
            <div class="duty-input-group" style="display:flex;align-items:flex-start;gap:0.5em;">
                <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;margin-top:0.5em;">P.</span>
                <textarea class="primary-duty-input duty-input" style="flex:1;min-height:56px;resize:vertical;overflow-x:hidden;overflow-y:auto;box-sizing:border-box;" placeholder="Primary duty / responsibility...">${primary}</textarea>
                <div style="width:36px;"></div>
            </div>
        </div>
    `;

    // SECONDARY DUTIES section
    container.innerHTML += `
        <div class="duty-section duty-secondary" style="margin-bottom:1rem;">
            <div style="display:flex; align-items:center; gap:0.75rem; margin-bottom:0.5rem;">
                <span style="font-weight:700; color:#2563eb;">Secondary Duties</span>
                <span style="color:#6b7280; font-size:0.9em;">(Additional duties — show 5 by default)</span>
            </div>
            <div id="secondaryDutiesList"></div>
        </div>
    `;

    const list = document.getElementById('secondaryDutiesList');
    secondary.forEach((duty, i) => {
        const idx = i + 1;
        const dutyHtml = document.createElement('div');
        dutyHtml.className = 'duty-input-group';
        dutyHtml.style = 'display:flex;align-items:flex-start;gap:0.5em;margin-bottom:0.5rem;';
        dutyHtml.innerHTML = `
            <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;margin-top:0.5em;">${idx}.</span>
            <textarea class="secondary-duty-input duty-input" style="flex:1;min-height:38px;resize:vertical;overflow-x:hidden;overflow-y:auto;box-sizing:border-box;" placeholder="Secondary duty ${idx}...">${duty}</textarea>
            <button type="button" class="remove-duty" title="Remove duty" style="align-self:flex-start;">×</button>
        `;
        // attach remove handler programmatically (avoid duplicate inline onclick attributes)
        const removeBtn = dutyHtml.querySelector('.remove-duty');
        removeBtn.addEventListener('click', () => removeDuty(removeBtn));
        list.appendChild(dutyHtml);
    });

    // Single controls area (only one set of Add / Clear / Counter)
    container.innerHTML += `
        <div style="display:flex;gap:0.75rem;align-items:center;margin-top:0.75rem;">
            <button class="btn btn-primary" type="button" id="addDutyBtn"><i class="fa fa-plus"></i> Add Duty</button>
            <button class="btn btn-outline" type="button" id="clearDutiesBtnInline"><i class="fas fa-eraser"></i> Clear Duties</button>
            <div style="flex:1"></div>
            <div id="dutyCounter" class="badge"></div>
        </div>
    `;

    // Wire up add / clear buttons (use delegated unified handlers)
    const addBtn = document.getElementById('addDutyBtn');
    if (addBtn) addBtn.addEventListener('click', () => window.addDuty && window.addDuty());

    const clearInline = document.getElementById('clearDutiesBtnInline');
    if (clearInline) {
        clearInline.addEventListener('click', () => {
            // reset primary + five secondaries
            formState.duties = ['', '', '', '', '', ''];
            renderDutyInputs(formState.duties);
            updateDutyCounter();
        });
    }

    // Auto-resize and input listeners for all duty textareas
    container.querySelectorAll('.duty-input').forEach(textarea => {
        textarea.addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = (this.scrollHeight + 2) + 'px';
            // Always keep formState in sync
            syncFormStateFromInputs();
            updateDutyCounter();
        });
        // initial resize
        textarea.style.height = 'auto';
        textarea.style.height = (textarea.scrollHeight + 2) + 'px';
    });

    updateDutyCounter();
}
window.renderDutyInputs = renderDutyInputs;

(function() {
    function addDuty() {
        // If secondary duty layout present, add a secondary duty
        const secondaryList = document.querySelector('#secondaryDutiesList');
        if (secondaryList) {
            const currentCount = secondaryList.querySelectorAll('.duty-input-group').length;
            const maxSecondary = 20;
            if (currentCount >= maxSecondary) return;

            const idx = currentCount + 1;
            const dutyGroup = document.createElement('div');
            dutyGroup.className = 'duty-input-group';
            dutyGroup.style = 'display:flex;align-items:flex-start;gap:0.5em;margin-bottom:0.5rem;';
            dutyGroup.innerHTML = `
                <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;margin-top:0.5em;">${idx}.</span>
                <textarea class="secondary-duty-input duty-input" style="flex:1;min-height:38px;resize:vertical;overflow-x:hidden;overflow-y:auto;box-sizing:border-box;" placeholder="Secondary duty ${idx}..."></textarea>
                <button type="button" class="remove-duty" title="Remove duty" style="align-self:flex-start;">×</button>
            `;
            // attach remove handler programmatically
            dutyGroup.querySelector('.remove-duty').addEventListener('click', () => removeDuty(dutyGroup.querySelector('.remove-duty')));
            secondaryList.appendChild(dutyGroup);

            const textarea = dutyGroup.querySelector('textarea');
            if (textarea) {
                textarea.addEventListener('input', function() {
                    this.style.height = 'auto';
                    this.style.height = (this.scrollHeight + 2) + 'px';
                    syncFormStateFromInputs();
                    updateDutyCounter();
                });
                textarea.style.height = 'auto';
                textarea.style.height = (textarea.scrollHeight + 2) + 'px';
            }

            updateDutyCounter();
            return;
        }

        // Fallback: legacy flat list mode
        const container = document.getElementById('dutiesContainer');
        if (!container) return;
        const dutyCount = container.querySelectorAll('.duty-input-group').length;
        if (dutyCount >= 20) return;

        const dutyGroup = document.createElement('div');
        dutyGroup.className = 'duty-input-group';
        dutyGroup.style = 'display:flex;align-items:flex-start;gap:0.5em;margin-bottom:0.5rem;';
        dutyGroup.innerHTML = `
            <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;margin-top:0.5em;">${dutyCount + 1}.</span>
            <input type="text" class="duty-input" placeholder="Duty/Responsibility ${dutyCount + 1}...">
            <button type="button" class="remove-duty" title="Remove duty">×</button>
        `;
        container.appendChild(dutyGroup);

        // Wire events
        const input = dutyGroup.querySelector('.duty-input');
        if (input) {
            input.addEventListener('input', function() {
                syncFormStateFromInputs();
                updateDutyCounter();
            });
        }
        const removeBtn = dutyGroup.querySelector('.remove-duty');
        if (removeBtn) removeBtn.addEventListener('click', () => removeDuty(removeBtn));

        updateDutyNumbers();
        updateDutyCounter();
    }

    function clearDuties() {
        const secondaryList = document.querySelector('#secondaryDutiesList');
        if (secondaryList) {
            // Reset primary + five secondaries (preserve layout)
            formState.duties = ['', '', '', '', '', ''];
            renderDutyInputs(formState.duties);
            updateDutyCounter();
            return;
        }

        // Fallback: flat list mode — clear and recreate 6 empty inputs
        const container = document.getElementById('dutiesContainer');
        if (!container) return;
        container.innerHTML = '';
        for (let i = 0; i < 6; i++) {
            const dutyGroup = document.createElement('div');
            dutyGroup.className = 'duty-input-group';
            dutyGroup.innerHTML = `
                <span class="duty-number" style="width:2em;text-align:right;font-weight:600;color:#2563eb;">${i + 1}.</span>
                <input type="text" class="duty-input" placeholder="Duty/Responsibility ${i + 1}...">
                <button type="button" class="remove-duty" title="Remove duty">×</button>
            `;
            container.appendChild(dutyGroup);
        }
        // Attach handlers for remove buttons and inputs
        container.querySelectorAll('.remove-duty').forEach(btn => btn.addEventListener('click', () => removeDuty(btn)));
        container.querySelectorAll('.duty-input').forEach(input => input.addEventListener('input', () => { syncFormStateFromInputs(); updateDutyCounter(); }));

        updateDutyNumbers();
        updateDutyCounter();
        syncFormStateFromInputs();
    }

    // Expose as globals (overrides previous definitions)
    window.addDuty = addDuty;
    window.clearDuties = clearDuties;

    // Wire both clear button IDs (existing UI uses two possible IDs) to unified clear behavior
    document.addEventListener('click', function(e) {
        const target = e.target.closest && e.target.closest('button') ? e.target.closest('button') : e.target;
        if (!target) return;
        if (target.id === 'clearDutiesBtn' || target.id === 'clearDutiesBtnInline') {
            e.preventDefault();
            clearDuties();
        }
    }, true);

})();

window.removeDuty = function(button) {
    // Accept either the button element or an index/callback usage
    let btn = button;
    if (!btn || !(btn instanceof Element)) {
        // nothing to remove
        return;
    }
    const group = btn.closest('.duty-input-group');
    if (group) {
        group.remove();
        // Re-number secondary duty numbers if secondary layout exists
        const secondaryList = document.querySelectorAll('#secondaryDutiesList .duty-input-group');
        secondaryList.forEach((g, i) => {
            const numSpan = g.querySelector('.duty-number');
            const textarea = g.querySelector('textarea');
            if (numSpan) numSpan.textContent = `${i + 1}.`;
            if (textarea) textarea.placeholder = `Secondary duty ${i + 1}...`;
        });
        // Also handle legacy flat list renumbering
        updateDutyNumbers();
        syncFormStateFromInputs();
        updateDutyCounter();
    }
};

// AI rewrite functionality
function setupAIRewriteButton(textareaId, resultId, buttonId) {
    const btn = document.getElementById(buttonId);
    if (!btn) return;

    btn.onclick = async function() {
        const textarea = document.getElementById(textareaId);
        const resultDiv = document.getElementById(resultId);
        if (!textarea || !resultDiv) return;

        const dutiesText = textarea.value.trim();
        if (!dutiesText) {
            resultDiv.innerHTML = '<span style="color:red;">Please enter duties to rewrite.</span>';
            return;
        }

        // Show spinner/loading
        resultDiv.innerHTML = `
            <div style="margin-bottom:1em; background:#f3f4f6; border-radius:0.5em; padding:1em; max-width:100%; word-break:break-word; white-space:pre-line;">
                <span class="spinner"></span> Rewriting duties with AI...
            </div>
        `;

        try {
            const response = await fetch('/api/rewrite-duties-sync', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ duties: dutiesText })
            });
            const data = await response.json();
            let rewritten = data.rewritten || '';

            // Split into lines, keep only non-empty lines, and add bullet points for display
            let rewrittenLines = rewritten
                .split('\n')
                .map(line => line.replace(/^\s*[\d]+[.)-]?\s*/g, '').replace(/^\s*[-•*]+\s*/g, '').trim())
                .filter(line => line.length > 0);

            // Show as bullet points in the result
            resultDiv.innerHTML = `
                <div style="margin-bottom:1em; background:#f3f4f6; border-radius:0.5em; padding:1em; max-width:100%; word-break:break-word; white-space:pre-line;">
                    <strong>Rewritten Duties:</strong>
                    <ul style="margin-top:0.5em; padding-left:1.5em;">
                        ${rewrittenLines.map(duty => `<li>${duty}</li>`).join('')}
                    </ul>
                </div>
                <button class="btn btn-primary" id="useRewritesBtn">
                    <i class="fas fa-arrow-right"></i> Use Rewrites
                </button>
            `;

            // When user clicks "Use Rewrites", fill the duties boxes (without bullets)
            document.getElementById('useRewritesBtn').onclick = function() {
                formState.unknownDuties = rewrittenLines;
                renderDutyInputs(formState.unknownDuties);

                // Enable the AI Recommendation button if 6 or more duties
                const getAIRecBtn = document.getElementById('getAIRecommendationBtn');
                if (getAIRecBtn) {
                    if (formState.unknownDuties.filter(d => d.trim().length > 0).length >= 6) {
                        getAIRecBtn.disabled = false;
                        getAIRecBtn.style.opacity = '1';
                        getAIRecBtn.style.cursor = 'pointer';
                        getAIRecBtn.classList.remove('btn-disabled');
                    } else {
                        getAIRecBtn.disabled = true;
                        getAIRecBtn.style.opacity = '0.6';
                        getAIRecBtn.style.cursor = 'not-allowed';
                        getAIRecBtn.classList.add('btn-disabled');
                    }
                }
                updateDutyCounter();

                // Show and enable the grade analysis button if 6+ duties
                const gradeBtn = document.getElementById('gradeAnalysisBtn');
                if (gradeBtn) {
                    if (formState.unknownDuties.filter(d => d.trim().length > 0).length >= 6) {
                        gradeBtn.style.display = '';
                        gradeBtn.disabled = false;
                        gradeBtn.style.opacity = '1';
                        gradeBtn.style.cursor = 'pointer';
                    } else {
                        gradeBtn.style.display = 'none';
                        gradeBtn.disabled = true;
                        gradeBtn.style.opacity = '0.6';
                        gradeBtn.style.cursor = 'not-allowed';
                    }
                }
            };
        } catch (err) {
            resultDiv.innerHTML = '<span style="color:red;">Error rewriting duties.</span>';
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

function removeDuplicateSummarySections(sections) {
    const summaryHeaders = [
        /^Total Points:/i,
        /^Final Grade:/i,
        /^Grade Range:/i
    ];
    const found = {};

    // Only keep the first occurrence of each summary header
    Object.keys(sections).forEach(key => {
        summaryHeaders.forEach(headerRegex => {
            if (headerRegex.test(key)) {
                if (found[headerRegex]) {
                    delete sections[key];
                } else {
                    found[headerRegex] = true;
                }
            }
        });
    });
    return sections;
}

window.removeDuplicateSummarySections = removeDuplicateSummarySections;
window.formatUpdatedPD = formatUpdatedPD;

// For updated PDs, keep splitting and formatting as before
function formatUpdatedPD(sectionsObject) {
    console.log('=== FORMAT UPDATED PD START ===');
    console.log('Input sections:', Object.keys(sectionsObject));
    
    const dedupedSections = cleanupMajorDutyDuplicates({ ...sectionsObject });
    const cleanedSections = removeDuplicateSummarySections(dedupedSections);
    
    console.log('After cleanup:', Object.keys(cleanedSections));
    
    // Debug: Check for summary sections with detailed info
    const summaryKeys = Object.keys(cleanedSections).filter(k => 
        /^Total Points:/i.test(k) || /^Final Grade:/i.test(k) || /^Grade Range:/i.test(k)
    );
    console.log('Summary sections in cleanedSections:', summaryKeys);

    // Define the correct section order
    const sectionOrder = [
        'HEADER', 
        'INTRODUCTION', 
        'MAJOR DUTIES',
        'MAJOR DUTY 1', 'MAJOR DUTY 2', 'MAJOR DUTY 3', 'MAJOR DUTY 4', 'MAJOR DUTY 5',
        'MAJOR DUTY 6', 'MAJOR DUTY 7', 'MAJOR DUTY 8', 'MAJOR DUTY 9', 'MAJOR DUTY 10',
        'Factor 1', 'Factor 2', 'Factor 3', 'Factor 4', 'Factor 5', 
        'Factor 6', 'Factor 7', 'Factor 8', 'Factor 9',
        'Total Points', 'Final Grade', 'Grade Range',
        'CONDITIONS OF EMPLOYMENT',
        'TITLE AND SERIES DETERMINATION',
        'FAIR LABOR STANDARDS ACT DETERMINATION'
    ];

    let formatted = '';
    let renderedKeys = new Set();

    // Process sections in order
    sectionOrder.forEach(sectionKey => {
        const key = findSectionKeyFlexible(cleanedSections, sectionKey);
        
        if (!key || renderedKeys.has(key)) return;
        
        let content = cleanedSections[key];
        
        // Special handling for summary sections - ALWAYS show them even with empty content
        if (sectionKey === 'Total Points' || sectionKey === 'Final Grade' || sectionKey === 'Grade Range') {
            console.log(`Adding summary section: "${key}"`);
            formatted += `\n\n**${key}**\n\n`;
            renderedKeys.add(key);
        } 
        // For all other sections, only show if they have content
        else if (content && content.trim()) {
            // HEADER: each variable on its own line
            if (sectionKey === 'HEADER') {
                content = content
                    .replace(/Job Series:\s*/i, 'Job Series: ')
                    .replace(/Position Title:\s*/i, '\nPosition Title: ')
                    .replace(/Agency:\s*/i, '\nAgency: ')
                    .replace(/Organization:\s*/i, '\nOrganization: ')
                    .replace(/Lowest Organization:\s*/i, '\nLowest Organization: ')
                    .replace(/Supervisory Level:\s*/i, '\nSupervisory Level: ')
                    .replace(/\n{2,}/g, '\n')
                    .trim();
            }
            // MAJOR DUTIES: split numbered duties onto their own lines
            if (sectionKey === 'MAJOR DUTIES' || /^MAJOR DUTY \d+$/i.test(sectionKey)) {
                content = content.replace(/(\d+\.\s)/g, '\n$1').replace(/\n{2,}/g, '\n').trim();
            }
            formatted += `\n\n**${key}**\n\n${content}\n`;
            renderedKeys.add(key);
        }
    });

    console.log('After ordered sections, checking remaining...');
    
    // Add any remaining sections not already rendered
    Object.keys(cleanedSections).forEach(key => {
        if (renderedKeys.has(key)) return;
        
        const isSummarySection = /^Total Points:/i.test(key) || /^Final Grade:/i.test(key) || /^Grade Range:/i.test(key);
        
        if (isSummarySection) {
            console.log(`Adding remaining summary section: "${key}"`);
            formatted += `\n\n**${key}**\n\n`;
            renderedKeys.add(key);
        } else if (cleanedSections[key] && cleanedSections[key].trim()) {
            formatted += `\n\n**${key}**\n\n${cleanedSections[key].trim()}\n`;
            renderedKeys.add(key);
        }
    });

    formatted = formatted.replace(/\n{3,}/g, '\n\n').trim();
    
    console.log('Final formatted length:', formatted.length);
    console.log('=== FORMAT UPDATED PD END ===');

    return formatted;
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
    // Normalize line endings and invisible whitespace
    text = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
    text = text.replace(/\u00A0/g, ' ').replace(/\u200B/g, ' ');

    // Conservative cleanup: collapse excessive blank lines
    text = text.replace(/\n{3,}/g, '\n\n');

    // --- Make sure "Factor4A"/"Factor4B" and similar run-together tokens are separated ---
    text = text.replace(/Factor\s*([0-9]{1,2})([A-Za-z])/gi, 'Factor $1$2'); // ensure a space after "Factor"
    text = text.replace(/Factor4A/gi, 'Factor 4A');
    text = text.replace(/Factor4B/gi, 'Factor 4B');

    // Normalize various dash characters to a single en-dash representation for matching
    text = text.replace(/[–—―]/g, '-');

    // Fix run-together "Level4-4,100Points" and similar
    text = text.replace(/Level\s*([0-9]+)\s*[-–—]?\s*([0-9]+)\s*,?\s*([0-9]{1,6})\s*Points/gi, 'Level $1-$2, $3 Points');
    text = text.replace(/(\d)(Points)/gi, '$1 Points');

    // Insert line breaks before factor headers that may be run together with previous text
    // This handles Factor X, Factor X A/B forms, with/without dash/text variations, and "Level" on same line
    text = text.replace(/(\bFactor\s*[0-9]{1,2}[A-Za-z]?\b[^\n]{0,140}?Level\s*\d+-\d+,\s*\d+\s*Points?)/gi, '\n$1');

    // Also ensure we break before explicit bold headers like **FACTOR EVALUATION**
    text = text.replace(/(\*\*[A-Z][A-Z0-9 \-&()\/,:%']+\*\*)/g, '\n$1');

    // Clean up multiple newlines again after inserts
    text = text.replace(/\n{3,}/g, '\n\n');

    // Section header regex: include bold headers OR Factor headers (with optional A/B), or summary lines
    const sectionHeaderRegex = /(?:\*\*([A-Z][A-Z0-9 \-:&(),%/]+)\*\*|^Factor\s*[0-9]{1,2}[A-Za-z]?\b[^\n]*?Level\s*[0-9]+-[0-9]+,\s*\d+\s*Points?|^Total Points:\s*\d+|^Final Grade:\s*GS-?\d+|^Grade Range:\s*\d+-\d+)/gim;

    // Find all section header matches and their indices
    let matches = [];
    let match;
    while ((match = sectionHeaderRegex.exec(text)) !== null) {
        // Determine header text: capture group 1 (bold header) or the full match
        let header = (match[1] && match[1].trim()) ? match[1].trim() : match[0].trim();
        header = header.replace(/^\*\*|\*\*$/g, '').trim();
        matches.push({ raw: match[0], title: header, index: match.index });
    }
    // Add sentinel end marker
    matches.push({ title: 'END', index: text.length });

    let sections = {};
    for (let i = 0; i < matches.length - 1; i++) {
        const headerRaw = matches[i].raw;
        const headerTitle = matches[i].title;
        const headerEnd = matches[i].index + headerRaw.length;
        const start = headerEnd;
        const end = matches[i + 1].index;
        let rawContent = text.substring(start, end).trim();

        // Strip surrounding asterisks strictly from content area only if present
        const cleanContent = rawContent.replace(/^\*{1,2}/gm, '').replace(/\*{1,2}$/gm, '').trim();

        // Normalize header canonical forms for Factor 4A/4B variations (keep Level/Points if present)
        if (/^Factor\s*4A\b/i.test(headerTitle)) {
            // Try to extract Level and Points if they were kept in headerRaw
            const lp = headerRaw.match(/Level\s*([0-9]+-[0-9]+)\s*,\s*(\d{1,6})\s*Points/i);
            const lvl = lp ? lp[1] : '';
            const pts = lp ? lp[2] : '';
            const canonical = lvl && pts
                ? `Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level ${lvl}, ${pts} Points`
                : `Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS)`;
            sections[canonical] = cleanContent;
            continue;
        }
        if (/^Factor\s*4B\b/i.test(headerTitle)) {
            const lp = headerRaw.match(/Level\s*([0-9]+-[0-9]+)\s*,\s*(\d{1,6})\s*Points/i);
            const lvl = lp ? lp[1] : '';
            const pts = lp ? lp[2] : '';
            const canonical = lvl && pts
                ? `Factor 4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level ${lvl}, ${pts} Points`
                : `Factor 4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS)`;
            sections[canonical] = cleanContent;
            continue;
        }

        // For other headers, just store cleaned title and content
        sections[headerTitle] = cleanContent;
    }

    // Fallback extraction for summary fields if not present as separate headers
    if (!Object.keys(sections).some(k => /^Total Points:/i.test(k))) {
        const tp = text.match(/Total Points[:\s]*([0-9]{3,5})/i);
        if (tp) sections[`Total Points: ${tp[1]}`] = '';
    }
    if (!Object.keys(sections).some(k => /^Final Grade:/i.test(k))) {
        const fg = text.match(/Final Grade[:\s]*(GS-?\d+)/i);
        if (fg) sections[`Final Grade: ${fg[1]}`] = '';
    }
    if (!Object.keys(sections).some(k => /^Grade Range:/i.test(k))) {
        const gr = text.match(/Grade Range[:\s]*([\d\-]+)/i);
        if (gr) sections[`Grade Range: ${gr[1]}`] = '';
    }

    // If no HEADER key, use the first block of lines as HEADER
    if (!sections['HEADER']) {
        const firstLines = text.split('\n').slice(0, 8).filter(l => l.trim()).join('\n');
        sections['HEADER'] = firstLines;
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

    // Split major duties into separate paragraphs if present
    let blocks = content.split(/\n\s*\n/);
    blocks = blocks.flatMap(block => {
        if (block.startsWith('**MAJOR DUTIES**') || block.startsWith('MAJOR DUTIES')) {
            // Split numbered duties
            return block.split(/(?=\n\d+\.\s)/g).map(duty => duty.trim()).filter(Boolean);
        }
        return [block];
    });

    const paragraphs = blocks.map(block => new Paragraph({
        children: [new TextRun(block)],
        spacing: { after: 200 }
    }));

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
//window.refreshJobSeriesData = initializeJobSeriesData;
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
        if (totalPointsMatch) pdSections[`Total Points: ${totalPointsMatch[1]}`] = `Total Points: ${totalPointsMatch[1]}`;
        const finalGradeMatch = text.match(/Final Grade\s*:\s*(GS-?\d+)/i);
        if (finalGradeMatch) pdSections[`Final Grade: ${finalGradeMatch[1]}`] = `Final Grade: ${finalGradeMatch[1]}`;
        const gradeRangeMatch = text.match(/Grade Range\s*:\s*([\d\-]+)/i);
        if (gradeRangeMatch) pdSections[`Grade Range: ${gradeRangeMatch[1]}`] = `Grade Range: ${gradeRangeMatch[1]}`;

        if (!pdSections['HEADER']) {
            const headerLines = text.split('\n').slice(0, 5).filter(l => l.trim()).join('\n');
            pdSections['HEADER'] = headerLines;
        }

        console.log('Final pdSections keys:', Object.keys(pdSections));
        renderUpdateStep(2);
    };
}

function loadSamplePD() {
    const sampleTxtUrl = '/General_Engineer_PD_Sample.txt';
    originalFileName = 'General_Engineer_PD_Sample.txt';

    fetch(sampleTxtUrl)
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            return response.text();
        })
        .then(text => {
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
            if (totalPointsMatch) pdSections[`Total Points: ${totalPointsMatch[1]}`] = `Total Points: ${totalPointsMatch[1]}`;
            const finalGradeMatch = text.match(/Final Grade\s*:\s*(GS-?\d+)/i);
            if (finalGradeMatch) pdSections[`Final Grade: ${finalGradeMatch[1]}`] = `Final Grade: ${finalGradeMatch[1]}`;
            const gradeRangeMatch = text.match(/Grade Range\s*:\s*([\d\-]+)/i);
            if (gradeRangeMatch) pdSections[`Grade Range: ${gradeRangeMatch[1]}`] = `Grade Range: ${gradeRangeMatch[1]}`;

            if (!pdSections['HEADER']) {
                const headerLines = text.split('\n').slice(0, 5).filter(l => l.trim()).join('\n');
                pdSections['HEADER'] = headerLines;
            }

            console.log('Sample PD - Final keys:', Object.keys(pdSections));
            renderUpdateStep(2);
        })
        .catch(error => {
            console.error('Failed to load sample PD text file:', error);
            alert(`Failed to load sample PD text file: ${error.message}\n\nPlease ensure the file 'General_Engineer_PD_Sample.txt' is placed in your public directory.`);
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
    const container = document.getElementById('editableSections');
    if (!container) return;

    container.innerHTML = '';
    const renderedSections = new Set();

    // 1. HEADER and INTRODUCTION
    ['HEADER', 'INTRODUCTION'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(pdSections, sectionKey);
        if (key && !renderedSections.has(key)) {
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
        }
    });

    // 2. MAJOR DUTIES section
    const majorDutyKeys = Object.keys(pdSections).filter(k =>
        /^MAJOR DUT(Y|IES)\s*\d*/i.test(k.trim())
    );
    let combinedMajorDuties = '';
    if (majorDutyKeys.length > 0) {
        combinedMajorDuties = majorDutyKeys
            .sort((a, b) => {
                const aNum = parseInt(a.match(/\d+/)?.[0] || '0', 10);
                const bNum = parseInt(b.match(/\d+/)?.[0] || '0', 10);
                return aNum - bNum;
            })
            .map(k => pdSections[k])
            .join('\n\n');
    } else if (pdSections['MAJOR DUTIES']) {
        combinedMajorDuties = pdSections['MAJOR DUTIES'];
    }
    if (combinedMajorDuties) {
        container.appendChild(createSectionDiv('MAJOR DUTIES', combinedMajorDuties));
        renderedSections.add('MAJOR DUTIES');
    }

    // 3. FACTOR sections (Factor 1 to Factor 9)
    for (let i = 1; i <= 9; i++) {
        const key = findSectionKeyFlexible(pdSections, `Factor ${i}`);
        if (key && !renderedSections.has(key)) {
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
        }
    }

    // 4. Immediately after factors, add summary sections
    ['Total Points', 'Final Grade', 'Grade Range'].forEach(sectionKey => {
        const key = findSectionKeyFlexible(pdSections, sectionKey);
        if (key && !renderedSections.has(key)) {
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
        }
    });

    // 5. Render all other sections except those already rendered
    Object.keys(pdSections).forEach(key => {
        if (!renderedSections.has(key) &&
            !/^MAJOR DUT(Y|IES)\s*\d*/i.test(key.trim()) &&
            !/^Factor\s*\d+/i.test(key.trim()) &&
            !['Total Points', 'Final Grade', 'Grade Range', 'HEADER', 'INTRODUCTION'].includes(key)
        ) {
            container.appendChild(createSectionDiv(key, pdSections[key]));
            renderedSections.add(key);
        }
    });
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
        const filtered = options.filter(opt =>
            opt.code.includes(term) ||
            (opt.name && opt.name.toLowerCase().includes(term))
        );
        list.innerHTML = filtered.length
            ? filtered.map(opt =>
                `<div class="dropdown-item" data-value="${opt.code}">${opt.code}${opt.name ? ' - ' + opt.name : ''}</div>`
            ).join('')
            : `<div class="dropdown-item disabled">No results</div>`;
        list.style.display = 'block';
    }

    list.addEventListener('mousedown', function(e) {
        if (e.target.classList.contains('dropdown-item') && !e.target.classList.contains('disabled')) {
            input.value = e.target.textContent;
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
            if (positionTitleInput) {
                positionTitleInput.value = '';
                positionTitleInput.disabled = true;
            }
            setupJobSeriesDropdown();
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

async function maybePrefillDutyFor0343() {
    if (formState.jobSeries === "0343" && formState.positionTitle) {
        try {
            // Prompt the backend AI for a unique, specific common duty for this series/title
            const response = await fetch('/api/generate-common-duty', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    jobSeries: formState.jobSeries,
                    positionTitle: formState.positionTitle
                })
            });
            const data = await response.json();
            if (data && data.duty) {
                // Pre-fill the first duty box with the AI-generated duty
                formState.duties = [data.duty, '', '', '', '', ''];
            } else {
                // Fallback: just use empty duties
                formState.duties = Array(6).fill('');
            }
        } catch (e) {
            // On error, fallback to empty duties
            formState.duties = Array(6).fill('');
        }
    } else {
        // For other series, do not prefill
        formState.duties = Array(6).fill('');
    }
}

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