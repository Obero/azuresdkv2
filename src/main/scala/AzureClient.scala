package io.gatling.thibaut.azuresdkv2

import AzureClient.runShellScript

import com.azure.core.credential.TokenCredential
import com.azure.core.http.policy.HttpLogDetailLevel
import com.azure.core.management.profile.AzureProfile
import com.azure.core.management.{AzureEnvironment, Region}
import com.azure.identity.EnvironmentCredentialBuilder
import com.azure.resourcemanager.AzureResourceManager
import com.azure.resourcemanager.compute.models.{RunCommandResult, VirtualMachine, VirtualMachineImage}
import com.azure.resourcemanager.marketplaceordering.MarketplaceOrderingManager
import com.azure.resourcemanager.marketplaceordering.fluent.models.AgreementTermsInner
import com.azure.resourcemanager.marketplaceordering.models.{AgreementTerms, OfferType}
import com.azure.resourcemanager.network.models.{Network, PublicIpAddress}
import com.azure.resourcemanager.resources.fluentcore.model.Creatable
import com.azure.resourcemanager.resources.models.ResourceGroup
import reactor.core.publisher.Mono

import scala.jdk.CollectionConverters.{IterableHasAsScala, SeqHasAsJava}

object AzureClient {
  final case class Image(publisher: String, offer: String, sku: String, plan: String, version: String = "latest", region: String)

  final case class ResourceGroupName(name: String) extends AnyVal {
    override def toString: String =
      name
        .take(90)
        .replaceAll("[.]+$", "")
        .replaceAll("[^\\w_.()-]", "")
  }

  final case class VirtualMachineName(name: String) extends AnyVal {
    override def toString: String =
      name
        .take(64)
        .replaceAll("[\\s~!@#$%^&*()=+_\\[\\]{}\\\\]", "")
  }

  def apply: AzureClient = {
    val profile = getAzureProfile
    val credential = getCredential
    val resourceManager = buildResourceManager(profile, credential)
    val marketplaceManager = buildMarketplaceManager(profile, credential)

    new AzureClient(resourceManager, marketplaceManager)
  }

  private def buildResourceManager(profile: AzureProfile, credential: TokenCredential): AzureResourceManager =
    AzureResourceManager.configure
      .withLogLevel(HttpLogDetailLevel.BASIC)
      .authenticate(credential, profile)
      .withDefaultSubscription

  private def buildMarketplaceManager(profile: AzureProfile, credential: TokenCredential) =
    MarketplaceOrderingManager.authenticate(credential, profile)

  private def getAzureProfile: AzureProfile =
    new AzureProfile(AzureEnvironment.AZURE)

  private def getCredential: TokenCredential =
    new EnvironmentCredentialBuilder().build()

  private def runShellScript(script: Seq[String], vm: VirtualMachine): Mono[RunCommandResult] =
    vm.runShellScriptAsync(script.asJava, List().asJava)
}

//TODO: manage subscription
class AzureClient private (resourceManager: AzureResourceManager, marketplaceManager: MarketplaceOrderingManager) {
  //  def runInstances(region: String, size: String, image: AzureClient.Image, rgName: String, vmName: String, nbInstances: Int): F[Unit] = ???

  def runInstance(region: String, size: String, image: AzureClient.Image, rgName: String, vmName: String) = {
    val rg: ResourceGroup = createResourceGroup(rgName, region)
    val vms: Iterable[VirtualMachine] = createVirtualMachines(size, rg, region, image, vmName, 3)

    vms.foreach(vm =>
      runShellScript(
        Seq(
          "touch /home/gatling/coucou.txt",
          "touch /home/gatling/coucou2.txt"
        ),
        vm
      )
    )
  }

  private def createVirtualMachines(
      size: String,
      rg: ResourceGroup,
      region: String,
      image: AzureClient.Image,
      baseVmName: String,
      vmNumber: Int
  ): Iterable[VirtualMachine] = {
    acceptAgreement(image)

    val azureImage: VirtualMachineImage = resourceManager.virtualMachineImages.getImage(
      image.region,
      image.publisher,
      image.offer,
      image.sku,
      image.version
    )

    val toCreate: Seq[Creatable[VirtualMachine]] = (1 to vmNumber).map { index =>
      val vmName = s"$baseVmName-$index"
      val ipName = s"public-ip-$vmName"

      resourceManager
        .virtualMachines()
        .define(vmName)
        .withRegion(region)
        .withExistingResourceGroup(rg)
        .withNewPrimaryNetwork("10.0.0.0/28")
        .withPrimaryPrivateIPAddressDynamic()
        .withNewPrimaryPublicIPAddress(ipName)
        .withLatestLinuxImage(azureImage.publisherName(), azureImage.offer(), azureImage.sku())
        .withRootUsername("gatling")
        .withRootPassword("gatl1ng!")
        .withSize(size)
        .withPlan(azureImage.plan())
    }

    resourceManager.virtualMachines().create(toCreate.asJava).values().asScala
  }

  private def acceptAgreement(image: AzureClient.Image): AgreementTerms = {
    val agreement: AgreementTerms = marketplaceManager
      .marketplaceAgreements()
      .get(
        OfferType.VIRTUALMACHINE,
        image.publisher,
        image.offer,
        image.plan
      )

    val acceptedTerms = new AgreementTermsInner()
      .withPublisher(agreement.publisher)
      .withProduct(agreement.product)
      .withPlan(agreement.plan)
      .withLicenseTextLink(agreement.licenseTextLink)
      .withPrivacyPolicyLink(agreement.privacyPolicyLink)
      .withMarketplaceTermsLink(agreement.marketplaceTermsLink)
      .withRetrieveDatetime(agreement.retrieveDatetime)
      .withSignature(agreement.signature)
      .withAccepted(true)

    marketplaceManager
      .marketplaceAgreements()
      .create(OfferType.VIRTUALMACHINE, image.publisher, image.offer, image.plan, acceptedTerms)
  }

  def createResourceGroup(
      resourceGroupName: String,
      region: String
  ): ResourceGroup =
    resourceManager
      .resourceGroups()
      .define(resourceGroupName)
      .withRegion(region)
      .create()

  def list_resource_groups(): Unit = {
    val resourceGroups = resourceManager.resourceGroups().list()
    for (resourceGroup <- resourceGroups.asScala)
      println(resourceGroup.name())
  }

  def deleteResourceGroup(resourceGroupName: String): Unit =
    resourceManager
      .resourceGroups()
      .deleteByName(resourceGroupName)

  private def createInterface(
      rg: ResourceGroup,
      ip: PublicIpAddress,
      networkSubnet: String,
      network: Network,
      interfaceName: String
  ) =
    resourceManager
      .networkInterfaces()
      .define(interfaceName)
      .withRegion(Region.US_EAST)
      .withExistingResourceGroup(rg)
      .withExistingPrimaryNetwork(network)
      .withSubnet(networkSubnet)
      .withPrimaryPrivateIPAddressDynamic()
      .withExistingPrimaryPublicIPAddress(ip)
      .create()

  private def createNetwork(
      rg: ResourceGroup,
      networkName: String,
      networkAddressSpace: String,
      networkSubnet: String,
      networkSubnetMask: String
  ) =
    resourceManager
      .networks()
      .define(networkName)
      .withRegion(Region.EUROPE_WEST)
      .withExistingResourceGroup(rg)
      .withAddressSpace(networkAddressSpace)
      .withSubnet(networkSubnet, networkSubnetMask)
      .create()

  private def createIp(
      ipname: String,
      region: String,
      rgName: String
  ): PublicIpAddress =
    resourceManager
      .publicIpAddresses()
      .define(ipname)
      .withRegion(region)
      .withExistingResourceGroup(rgName)
      .withDynamicIP()
      .create()
}
