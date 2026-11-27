SELECT *
From PortfolioProject..coviddeaths$
Where Continent is not null
order by 3,4

--SELECT *
--From PortfolioProject..covidvax$
--order by 3,4

-- select data we are going to be using

Select Location, date, total_cases, new_cases, total_deaths, population
From PortfolioProject..coviddeaths$
order by 1,2

-- Looking at total cases vs total deaths
-- shows percentage  of dying if you contract covid in your country
Select Location, date, total_cases, total_deaths, (total_deaths/total_cases)*100 as DeathPercentage
From PortfolioProject..coviddeaths$
Where location like '%india%'
order by 1,2

-- looking at total cases vs population
-- shows what percentage of population got covid
Select Location, date, total_cases, population, (total_cases/population)*100 as PercentPopulationInfected
From PortfolioProject..coviddeaths$
--Where location like '%india%'
order by 1,2

-- Looking at countries with highiest infection rate compared to population
Select Location,population,MAX(total_cases) as HighestInfectionCount,  Max((total_cases/population))*100 as PercentPopulationInfected
From PortfolioProject..coviddeaths$
--Where location like '%india%'
Group by location, population
order by PercentPopulationInfected desc

-- Showing COuntries with Highest Death count per population
Select Location, Max(cast(Total_deaths as int)) as TotalDeathCount
From PortfolioProject..coviddeaths$
--Where location like '%india%'
Where Continent is not null
Group by Location
order by TotalDeathCount desc


--lets break things down by continents



-- showing the continets with ighest death count 

Select continent, Max(cast(Total_deaths as int)) as TotalDeathCount
From PortfolioProject..coviddeaths$
--Where location like '%india%'
Where Continent is not null
Group by continent
order by TotalDeathCount desc


-- Global Numbers

Select  SUM(new_cases) as total_cases, SUM(cast(new_deaths as int ))as total_deaths, SUM(cast(New_deaths as int))/SUM(New_cases)*100 as DeathPercentage
From PortfolioProject..coviddeaths$
--Where location like '%india%'
where continent is not null
--Group by date
order by 1,2

-- Looking at total population vs vaccination

-- USE CTE

With PopvsVac (Continent, Location, Date, Population,New_vaccinations, RollingpeopleVaccinated)
as
(
Select dea.continent, dea.location, dea.date, dea.population, vac.new_vaccinations
, SUM(cast(vac.new_vaccinations as bigint)) OVER (Partition by dea.location order by dea.location, dea.Date) as RollingPeopleVaccinated
--, (RollingPeopleVaccinated/population)*100
From PortfolioProject..coviddeaths$ dea
Join PortfolioProject..covidvax$ vac
	On dea.location = vac.location
	and dea.date = vac.date
where dea.continent is not null
--order by 2,3
)
Select *, (RollingpeopleVaccinated/Population)*100
From PopvsVac

--TEMP Table
DROP Table if exists #PercentPopulationvaccinated
Create Table #PercentPopulationvaccinated
(
Continent nvarchar(255),
Location Nvarchar(255),
Date datetime,
Population numeric,
New_vaccinations numeric,
RollingPeopleVaccinated numeric
)

Insert into #PercentPopulationvaccinated
Select dea.continent, dea.location, dea.date, dea.population, vac.new_vaccinations
, SUM(cast(vac.new_vaccinations as bigint)) OVER (Partition by dea.location order by dea.location, dea.Date) as RollingPeopleVaccinated
--, (RollingPeopleVaccinated/population)*100
From PortfolioProject..coviddeaths$ dea
Join PortfolioProject..covidvax$ vac
	On dea.location = vac.location
	and dea.date = vac.date
--where dea.continent is not null
--order by 2,3

Select *, (RollingpeopleVaccinated/Population)*100
From #PercentPopulationvaccinated

-- creating view to store data for later visualisations
Create view PercentPopulationvaccinated as 
Select dea.continent, dea.location, dea.date, dea.population, vac.new_vaccinations
, SUM(cast(vac.new_vaccinations as bigint)) OVER (Partition by dea.location order by dea.location, dea.Date) as RollingPeopleVaccinated
--, (RollingPeopleVaccinated/population)*100
From PortfolioProject..coviddeaths$ dea
Join PortfolioProject..covidvax$ vac
	On dea.location = vac.location
	and dea.date = vac.date
where dea.continent is not null
--order by 2,3

Select *
From PercentPopulationvaccinated